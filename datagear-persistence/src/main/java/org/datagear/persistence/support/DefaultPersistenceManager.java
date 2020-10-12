/*
 * Copyright 2018 datagear.tech. All Rights Reserved.
 */

package org.datagear.persistence.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.datagear.connection.ConnectionOption;
import org.datagear.meta.Column;
import org.datagear.meta.PrimaryKey;
import org.datagear.meta.Table;
import org.datagear.persistence.Dialect;
import org.datagear.persistence.DialectSource;
import org.datagear.persistence.LiteralSqlParamValue;
import org.datagear.persistence.NonUniqueResultException;
import org.datagear.persistence.PagingData;
import org.datagear.persistence.PagingQuery;
import org.datagear.persistence.PersistenceException;
import org.datagear.persistence.PersistenceManager;
import org.datagear.persistence.Query;
import org.datagear.persistence.Row;
import org.datagear.persistence.RowMapper;
import org.datagear.persistence.SqlParamValueMapper;
import org.datagear.util.JDBCCompatiblity;
import org.datagear.util.Sql;
import org.datagear.util.SqlParamValue;
import org.datagear.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认{@linkplain PersistenceManager}。
 * 
 * @author datagear@163.com
 *
 */
public class DefaultPersistenceManager extends PersistenceSupport implements PersistenceManager
{
	private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPersistenceManager.class);

	private DialectSource dialectSource;

	public DefaultPersistenceManager()
	{
		super();
	}

	public DefaultPersistenceManager(DialectSource dialectSource)
	{
		super();
		this.dialectSource = dialectSource;
	}

	@Override
	public DialectSource getDialectSource()
	{
		return dialectSource;
	}

	public void setDialectSource(DialectSource dialectSource)
	{
		this.dialectSource = dialectSource;
	}

	@Override
	public Row insert(Connection cn, Table table, Row row) throws PersistenceException
	{
		return insert(cn, null, table, row, null);
	}

	@Override
	public Row insert(Connection cn, Dialect dialect, Table table, Row row, SqlParamValueMapper mapper)
			throws PersistenceException
	{
		checkValidTable(table);

		dialect = getDialect(cn, dialect);

		// 用于避免SQL参数转换中出现异常导致已转换的资源无法释放
		ReleasableRegistry releasableRegistry = createReleasableRegistry();

		Sql sql = Sql.valueOf().sql("INSERT INTO ").sql(quote(dialect, table.getName())).sql(" (").delimit(",");
		Sql valueSql = Sql.valueOf().sql(" VALUES (").delimit(",");

		Column[] columns = table.getColumns();
		List<Column> autoGeneratedColumns = new ArrayList<>(2);

		AutoGeneratedResult generatedResult;

		try
		{
			for (int i = 0; i < columns.length; i++)
			{
				Column column = columns[i];

				// 忽略不支持的列，避免程序不可用
				if (!supportsColumn(column))
					continue;

				String name = column.getName();
				Object value = row.get(name);

				// 如果是自动生成列且没有初值，才返回自动生成值，因为即使自动生成列，也是允许手动赋值的
				if (column.isAutoincrement() && StringUtil.isEmpty(value))
				{
					autoGeneratedColumns.add(column);

					// 忽略，留给数据库自动生成
					continue;
				}

				// 忽略不插入的列
				if (!row.containsKey(name))
					continue;

				// 忽略null值，留给数据库生成默认值
				if (value == null)
					continue;

				SqlParamValue sqlParamValue = mapToSqlParamValue(cn, table, column, value, mapper, releasableRegistry);

				sql.sqld(quote(dialect, name));

				if (sqlParamValue instanceof LiteralSqlParamValue)
					valueSql.sqld(addBracketIfSelectSql(((LiteralSqlParamValue) sqlParamValue).getValue()));
				else
					valueSql.sqld("?").param(sqlParamValue);
			}

			sql.sql(")");
			valueSql.sql(")");
			sql.sql(valueSql);

			generatedResult = executeUpdateWrap(cn, sql, autoGeneratedColumns);
		}
		finally
		{
			releasableRegistry.release();
		}

		Row re = new Row(row);

		if (generatedResult != null && generatedResult.hasGeneratedResult())
		{
			Map<String, Object> gen = generatedResult.getGeneratedResult().get(0);
			re.putAll(gen);
		}

		return re;
	}

	@Override
	public int update(Connection cn, Table table, Row origin, Row update) throws PersistenceException
	{
		return update(cn, null, table, origin, update, null);
	}

	@Override
	public int update(Connection cn, Dialect dialect, Table table, Row origin, Row update, SqlParamValueMapper mapper)
			throws PersistenceException
	{
		checkValidTable(table);

		dialect = getDialect(cn, dialect);

		// 用于避免SQL参数转换中出现异常导致已转换的资源无法释放
		ReleasableRegistry releasableRegistry = createReleasableRegistry();

		Sql sql = Sql.valueOf().sql("UPDATE ").sql(quote(dialect, table.getName())).sql(" SET ").delimit(",");

		Column[] columns = table.getColumns();
		PrimaryKey primaryKey = table.getPrimaryKey();

		try
		{
			int updateColumnCount = 0;

			for (int i = 0; i < columns.length; i++)
			{
				Column column = columns[i];

				// 忽略不支持的列，避免程序不可用
				if (!supportsColumn(column))
					continue;

				String name = column.getName();

				if (!update.containsKey(name))
					continue;

				Object value = update.get(name);

				SqlParamValue sqlParamValue = mapToSqlParamValue(cn, table, column, value, mapper, releasableRegistry);

				@JDBCCompatiblity("某些数据库存在不允许更新自增长列或者主键列的情况（比如SQL Server的自增列），因此，如果这些列值没有改变，则不应更新")
				boolean checkIgnoreIfEquals = (column.isAutoincrement()
						|| (primaryKey != null && primaryKey.containsColumnName(name)));

				if (checkIgnoreIfEquals && StringUtil.isEquals(sqlParamValue.getValue(), origin.get(name)))
					continue;

				if (sqlParamValue instanceof LiteralSqlParamValue)
					sql.sqld(quote(dialect, name) + "="
							+ addBracketIfSelectSql(((LiteralSqlParamValue) sqlParamValue).getValue()));
				else
					sql.sqld(quote(dialect, name) + "=?").param(sqlParamValue);

				updateColumnCount++;
			}

			// 即使updateColumnCount=0也执行下面这行SQL拼接操作，确保必要的buildUniqueRecordCondition里面必要的校验逻辑执行到
			sql.sql(" WHERE ").sql(buildUniqueRecordCondition(cn, dialect, table, origin, mapper, releasableRegistry));

			if (updateColumnCount > 0)
			{
				return executeUpdateWrap(cn, sql);
			}
			else
			{
				// 没有需要更新的列时仍然返回1，确保返回逻辑正确
				return 1;
			}
		}
		finally
		{
			releasableRegistry.release();
		}
	}

	@Override
	public int delete(Connection cn, Table table, Row... rows) throws PersistenceException
	{
		return delete(cn, null, table, rows, null);
	}

	@Override
	public int delete(Connection cn, Dialect dialect, Table table, Row row, SqlParamValueMapper mapper)
			throws PersistenceException
	{
		return delete(cn, dialect, table, new Row[] { row }, mapper);
	}

	@Override
	public int delete(Connection cn, Dialect dialect, Table table, Row[] rows, SqlParamValueMapper mapper)
			throws PersistenceException
	{
		checkValidTable(table);

		dialect = getDialect(cn, dialect);

		// 用于避免SQL参数转换中出现异常导致已转换的资源无法释放
		ReleasableRegistry releasableRegistry = createReleasableRegistry();

		try
		{
			int count = 0;

			for (int i = 0; i < rows.length; i++)
			{
				Row row = rows[i];

				Sql sql = Sql.valueOf().sql("DELETE FROM ").sql(quote(dialect, table.getName())).sql(" WHERE ");
				sql.sql(buildUniqueRecordCondition(cn, dialect, table, row, mapper, releasableRegistry));
				count += executeUpdate(cn, sql);

				releasableRegistry.releaseClear();
			}

			return count;
		}
		catch (SQLException e)
		{
			throw new PersistenceException(e);
		}
		finally
		{
			releasableRegistry.release();
		}
	}

	@Override
	public int delete(Connection cn, Table table, Query query) throws PersistenceException
	{
		return delete(cn, null, table, query);
	}

	@Override
	public int delete(Connection cn, Dialect dialect, Table table, Query query) throws PersistenceException
	{
		checkValidTable(table);

		// TODO
		throw new UnsupportedOperationException("TODO");
	}

	@Override
	public Row get(Connection cn, Table table, Row param) throws NonUniqueResultException, PersistenceException
	{
		return get(cn, null, table, param, null, null);
	}

	@Override
	public Row get(Connection cn, Dialect dialect, Table table, Row param, SqlParamValueMapper sqlParamValueMapper,
			RowMapper rowMapper) throws NonUniqueResultException, PersistenceException
	{
		checkValidTable(table);

		dialect = getDialect(cn, dialect);

		// 用于避免SQL参数转换中出现异常导致已转换的资源无法释放
		ReleasableRegistry releasableRegistry = createReleasableRegistry();

		Sql sql = Sql.valueOf().sql("SELECT * FROM ").sql(quote(dialect, table.getName())).sql(" WHERE ");

		try
		{
			sql.sql(buildUniqueRecordCondition(cn, dialect, table, param, sqlParamValueMapper, releasableRegistry));

			List<Row> rows = executeListQuery(cn, table, sql, ResultSet.TYPE_FORWARD_ONLY, rowMapper);

			if (rows.size() == 1)
				return rows.get(0);
			else if (rows.size() < 1)
				return null;
			else
				throw new NonUniqueResultException();
		}
		finally
		{
			releasableRegistry.release();
		}
	}

	@Override
	public List<Row> query(Connection cn, Table table, Query query) throws PersistenceException
	{
		return query(cn, null, table, query, null);
	}

	@Override
	public List<Row> query(Connection cn, Dialect dialect, Table table, Query query, RowMapper mapper)
			throws PersistenceException
	{
		checkValidTable(table);

		dialect = getDialect(cn, dialect);

		Sql sql = buildQuerySql(cn, dialect, table, query, true);
		return executeListQuery(cn, table, sql, ResultSet.TYPE_FORWARD_ONLY, mapper);
	}

	@Override
	public PagingData<Row> pagingQuery(Connection cn, Table table, PagingQuery pagingQuery) throws PersistenceException
	{
		return pagingQuery(cn, null, table, pagingQuery, null);
	}

	@Override
	public PagingData<Row> pagingQuery(Connection cn, Dialect dialect, Table table, PagingQuery pagingQuery,
			RowMapper mapper) throws PersistenceException
	{
		checkValidTable(table);

		dialect = getDialect(cn, dialect);

		Sql queryView = buildQuerySql(cn, dialect, table, pagingQuery, true);

		long total = queryCount(cn, queryView);

		PagingData<Row> pagingData = new PagingData<>(pagingQuery.getPage(), total, pagingQuery.getPageSize());

		Sql query = null;
		List<Row> rows = null;
		int startRow = pagingData.getStartRow();
		int count = pagingData.getPageSize();

		if (dialect.supportsPagingSql())
		{
			query = dialect.toPagingQuerySql(queryView, pagingQuery.getOrders(), startRow, count);

			// 数据库分页
			if (query != null)
			{
				startRow = 1;
				count = -1;
			}
		}

		// 内存分页
		if (query == null)
		{
			if (LOGGER.isWarnEnabled())
				LOGGER.warn("memory pagination will be used for [{}]", ConnectionOption.valueOfNonNull(cn));

			query = dialect.toOrderSql(queryView, pagingQuery.getOrders());
		}

		rows = executeListQuery(cn, table, query, ResultSet.TYPE_SCROLL_INSENSITIVE, startRow, count, mapper);

		pagingData.setItems(rows);

		return pagingData;
	}

	@Override
	public String getQuerySql(Connection cn, Table table, Query query)
	{
		return getQuerySql(cn, null, table, query);
	}

	@Override
	public String getQuerySql(Connection cn, Dialect dialect, Table table, Query query)
	{
		checkValidTable(table);

		dialect = getDialect(cn, dialect);

		Sql sql = buildQuerySql(cn, dialect, table, query, false);
		sql = dialect.toOrderSql(sql, query.getOrders());

		return sql.getSqlValue();
	}

	protected long queryCount(Connection cn, Sql query)
	{
		Sql countQuery = Sql.valueOf().sql("SELECT COUNT(*) FROM (").sql(query).sql(") T");

		long re = executeCountQueryWrap(cn, countQuery);

		return re;
	}

	protected Sql buildQuerySql(Connection cn, Dialect dialect, Table table, Query query, boolean parameterized)
	{
		Sql sql = Sql.valueOf().sql("SELECT ").delimit(",");

		Column[] columns = table.getColumns();
		for (int i = 0; i < columns.length; i++)
			sql.sqld(quote(dialect, columns[i].getName()));

		sql.sql(" FROM ");
		sql.sql(quote(dialect, table.getName()));
		Sql condition = buildQueryCondition(cn, dialect, table, query, parameterized);

		if (!Sql.isEmpty(condition))
		{
			sql.sql(" WHERE ");
			sql.sql(condition);
		}

		return sql;
	}

	/**
	 * 构建查询条件。
	 * 
	 * @param cn
	 * @param dialect
	 * @param table
	 * @param query
	 * @param parameterized
	 * @return 返回{@code null}表示无条件
	 */
	protected Sql buildQueryCondition(Connection cn, Dialect dialect, Table table, Query query, boolean parameterized)
	{
		if (query == null)
			return null;

		if (!query.hasKeyword() && !query.hasCondition())
			return null;

		String conditionStr = (query.hasCondition() ? query.getCondition().trim() : null);
		boolean hasCondition = !StringUtil.isEmpty(conditionStr);

		Sql keywordCondition = dialect.toKeywordQueryCondition(table, query, parameterized);

		if (!hasCondition)
		{
			return keywordCondition;
		}
		else
		{
			Sql sql = Sql.valueOf();

			if (Sql.isEmpty(keywordCondition))
				sql.sql(conditionStr);
			else
				sql.sql("(").sql(conditionStr).sql(")").sql(" AND (").sql(keywordCondition).sql(")");

			return sql;
		}
	}

	/**
	 * 尝试构建能够确定唯一行记录的查询条件。
	 * <p>
	 * 注意：如果表没有主键和唯一键，返回的查询结果不一定是能够确定唯一行的。
	 * </p>
	 * 
	 * @param cn
	 * @param dialect
	 * @param table
	 * @param row
	 * @param mapper
	 *            允许为{@code null}
	 * @param releasableRegistry
	 * @return
	 * @throws PersistenceException
	 */
	protected Sql buildUniqueRecordCondition(Connection cn, Dialect dialect, Table table, Row row,
			SqlParamValueMapper mapper, ReleasableRegistry releasableRegistry) throws PersistenceException
	{
		Column[] columns = getUniqueRecordColumns(table);

		Sql sql = Sql.valueOf().delimit(" AND ");

		for (int i = 0; i < columns.length; i++)
		{
			Column column = columns[i];
			String name = column.getName();

			Object value = row.get(name);

			SqlParamValue sqlParamValue = mapToSqlParamValue(cn, table, column, value, mapper, releasableRegistry);

			if (sqlParamValue instanceof LiteralSqlParamValue)
				sql.sqld(quote(dialect, name) + "="
						+ addBracketIfSelectSql(((LiteralSqlParamValue) sqlParamValue).getValue()));
			else if (sqlParamValue.hasValue())
				sql.sqld(quote(dialect, name) + "=?").param(sqlParamValue);
			else
				sql.sqld(quote(dialect, name) + " IS NULL");
		}

		return sql;
	}

	/**
	 * 尝试获取能确定唯一行记录的列数组。
	 * 
	 * @param table
	 * @return
	 * @throws NonUniqueRecordColumnException
	 */
	protected Column[] getUniqueRecordColumns(Table table) throws NonUniqueRecordColumnException
	{
		Column[] columns = null;

		if (table.hasPrimaryKey())
			columns = table.getColumns(table.getPrimaryKey().getColumnNames());
		else if (table.hasUniqueKey())
			columns = table.getColumns(table.getUniqueKeys()[0].getColumnNames());
		else
			columns = getColumnsMaybeUniqueRecord(table);

		if (columns == null || columns.length == 0)
			throw new NonUniqueRecordColumnException("can not build unique row condition");

		return columns;
	}

	/**
	 * 获取可能用于作为行唯一行记标识的{@linkplain Column}数组。
	 * 
	 * @param table
	 * @return
	 */
	protected Column[] getColumnsMaybeUniqueRecord(Table table)
	{
		List<Column> re = new ArrayList<>();

		Column[] columns = table.getColumns();
		for (int i = 0; i < columns.length; i++)
		{
			int type = columns[i].getType();

			if (Types.BIGINT == type || Types.BIT == type || Types.BOOLEAN == type || Types.CHAR == type
					|| Types.DATE == type || Types.DECIMAL == type || Types.DOUBLE == type || Types.FLOAT == type
					|| Types.BINARY == type || Types.VARBINARY == type || Types.INTEGER == type || Types.NULL == type
					|| Types.NUMERIC == type || Types.REAL == type || Types.SMALLINT == type || Types.TIME == type
					|| Types.TIME_WITH_TIMEZONE == type || Types.TIMESTAMP == type
					|| Types.TIMESTAMP_WITH_TIMEZONE == type || Types.TINYINT == type || Types.VARCHAR == type)
				re.add(columns[i]);
		}

		return re.toArray(new Column[re.size()]);
	}

	/**
	 * 
	 * @param cn
	 * @param table
	 * @param column
	 * @param value
	 *            允许为{@code null}
	 * @param mapper
	 *            允许为{@code null}
	 * @param releasableRegistry
	 * @return
	 * @throws PersistenceException
	 */
	protected SqlParamValue mapToSqlParamValue(Connection cn, Table table, Column column, Object value,
			SqlParamValueMapper mapper, ReleasableRegistry releasableRegistry) throws PersistenceException
	{
		SqlParamValue sqlParamValue = null;

		if (mapper == null)
			sqlParamValue = createSqlParamValue(column, value);
		else
			sqlParamValue = mapper.map(cn, table, column, value);

		releasableRegistry.register(sqlParamValue.getValue());

		return sqlParamValue;
	}

	/**
	 * 
	 * @param cn
	 * @param init
	 *            允许为{@code null}
	 * @return
	 * @throws PersistenceException
	 */
	protected Dialect getDialect(Connection cn, Dialect init) throws PersistenceException
	{
		if (init != null)
			return init;

		return this.dialectSource.getDialect(cn);
	}

	protected ReleasableRegistry createReleasableRegistry()
	{
		return new ReleasableRegistry();
	}

	/**
	 * 如果是“SELECT”语句，则在前后添加括号：<code>(SELECT ...)</code>
	 * 
	 * @param sql
	 * @return
	 */
	protected String addBracketIfSelectSql(String sql)
	{
		if (!isSelectSql(sql))
			return sql;

		return "(" + sql + ")";
	}

	protected void checkValidTable(Table table)
	{
		if (!table.hasColumn())
			throw new NoColumnDefinedException(table.getName());
	}

	/**
	 * 判断给定SQL语句是否是“SELECT”语句。
	 * 
	 * @param sql
	 * @return
	 */
	public static boolean isSelectSql(String sql)
	{
		if (sql == null || sql.isEmpty())
			return false;

		return Pattern.matches(SELECT_SQL_REGEX, sql);
	}

	protected static final String SELECT_SQL_REGEX = "^\\s*((?i)select)\\s+\\S+[\\s\\S]*$";
}
