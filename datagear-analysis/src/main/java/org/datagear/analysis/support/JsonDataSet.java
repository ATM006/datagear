/*
 * Copyright (c) 2018 datagear.tech. All Rights Reserved.
 */

/**
 * 
 */
package org.datagear.analysis.support;

import java.util.List;
import java.util.Map;

import org.datagear.analysis.DataSetException;
import org.datagear.analysis.DataSetProperty;
import org.datagear.analysis.DataSetResult;

/**
 * JSON数据集。
 * 
 * @author datagear@163.com
 *
 */
public class JsonDataSet extends AbstractDataSet
{
	public JsonDataSet()
	{
		super();
	}

	public JsonDataSet(String id, String name, List<DataSetProperty> properties)
	{
		super(id, name, properties);
	}

	@Override
	public DataSetResult getResult(Map<String, ?> paramValues) throws DataSetException
	{
		return null;
	}
}
