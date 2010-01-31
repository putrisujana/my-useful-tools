/*
 *  Copyright 2004 Clinton Begin
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.ibatis.sqlmap.engine.mapping.result;

import com.ibatis.common.beans.Probe;
import com.ibatis.common.beans.ProbeFactory;
import com.ibatis.common.jdbc.exception.NestedSQLException;
import com.ibatis.common.resources.Resources;
import com.ibatis.common.exception.NestedRuntimeException;
import com.ibatis.sqlmap.client.SqlMapException;
import com.ibatis.sqlmap.engine.exchange.DataExchange;
import com.ibatis.sqlmap.engine.impl.ExtendedSqlMapClient;
import com.ibatis.sqlmap.engine.impl.SqlMapExecutorDelegate;
import com.ibatis.sqlmap.engine.mapping.result.loader.ResultLoader;
import com.ibatis.sqlmap.engine.mapping.sql.Sql;
import com.ibatis.sqlmap.engine.mapping.statement.MappedStatement;
import com.ibatis.sqlmap.engine.scope.ErrorContext;
import com.ibatis.sqlmap.engine.scope.RequestScope;
import com.ibatis.sqlmap.engine.type.DomCollectionTypeMarker;
import com.ibatis.sqlmap.engine.type.DomTypeMarker;
import com.ibatis.sqlmap.engine.type.TypeHandler;
import com.ibatis.sqlmap.engine.type.TypeHandlerFactory;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Basic implementation of ResultMap interface
 */
public class BasicResultMap implements ResultMap {

  private static final Probe PROBE = ProbeFactory.getProbe();

  private String id;
  private Class resultClass;

  // DO NOT ACCESS EITHER OF THESE OUTSIDE OF THEIR BEAN GETTER/SETTER
  private ResultMapping[] resultMappings;
  private ThreadLocal remappableResultMappings = new ThreadLocal();

  private DataExchange dataExchange;

  private List nestedResultMappings;

  private Discriminator discriminator;

  private Set groupByProps;

  private String xmlName;

  private String resource;

  private SqlMapExecutorDelegate delegate;

  protected boolean allowRemapping = false;

  /**
   * Constructor to pass a SqlMapExecutorDelegate in
   *
   * @param delegate - the SqlMapExecutorDelegate
   */
  public BasicResultMap(SqlMapExecutorDelegate delegate) {
    this.delegate = delegate;
  }

  /**
   * Getter for the SqlMapExecutorDelegate
   *
   * @return - the delegate
   */
  public SqlMapExecutorDelegate getDelegate() {
    return delegate;
  }

  public String getId() {
    return id;
  }

  /**
   * Setter for the ID
   *
   * @param id - the new ID
   */
  public void setId(String id) {
    this.id = id;
  }

  public Class getResultClass() {
    return resultClass;
  }

  public Object getUniqueKey(Object[] values) {
    if (groupByProps != null) {
      StringBuffer keyBuffer = new StringBuffer();
      for (int i = 0; i < getResultMappings().length; i++) {
        String propertyName = getResultMappings()[i].getPropertyName();
        if (groupByProps.contains(propertyName)) {
          keyBuffer.append(values[i]);
          keyBuffer.append('-');
        }
      }
      if (keyBuffer.length() < 1) {
        return null;
      } else {
        return keyBuffer.toString();
      }
    } else {
      return null;
    }
  }

  /**
   * Setter for the result class (what the results will be mapped into)
   *
   * @param resultClass - the result class
   */
  public void setResultClass(Class resultClass) {
    this.resultClass = resultClass;
  }

  /**
   * Getter for the DataExchange object to be used
   *
   * @return - the DataExchange object
   */
  public DataExchange getDataExchange() {
    return dataExchange;
  }

  /**
   * Setter for the DataExchange object to be used
   *
   * @param dataExchange - the new DataExchange object
   */
  public void setDataExchange(DataExchange dataExchange) {
    this.dataExchange = dataExchange;
  }

  /**
   * Getter (used by DomDataExchange) for the xml name of the results
   *
   * @return - the name
   */
  public String getXmlName() {
    return xmlName;
  }

  /**
   * Setter (used by the SqlMapBuilder) for the xml name of the results
   *
   * @param xmlName - the name
   */
  public void setXmlName(String xmlName) {
    this.xmlName = xmlName;
  }

  /**
   * Getter for the resource (used to report errors)
   *
   * @return - the resource
   */
  public String getResource() {
    return resource;
  }

  /**
   * Setter for the resource (used by the SqlMapBuilder)
   *
   * @param resource - the resource name
   */
  public void setResource(String resource) {
    this.resource = resource;
  }

  public void addGroupByProperty(String name) {
    if (groupByProps == null) {
      groupByProps = new HashSet();
    }
    groupByProps.add(name);
  }

  public boolean hasGroupBy() {
    return groupByProps != null && groupByProps.size() > 0;
  }

  public Iterator groupByProps() {
    return groupByProps.iterator();
  }

  public void addNestedResultMappings(ResultMapping mapping) {
    if (nestedResultMappings == null) {
      nestedResultMappings = new ArrayList();
    }
    nestedResultMappings.add(mapping);
  }

  public ResultMapping[] getResultMappings() {
    if (allowRemapping) {
      return (ResultMapping[]) remappableResultMappings.get();
    } else {
      return resultMappings;
    }
  }

  public void setDiscriminator (Discriminator discriminator) {
    if (this.discriminator != null) {
      throw new SqlMapException ("A discriminator may only be set once per result map.");
    }
    this.discriminator = discriminator;
  }

  public Discriminator getDiscriminator() {
    return discriminator;
  }

  public ResultMap resolveSubMap (RequestScope request, ResultSet rs) throws SQLException {
    ResultMap subMap = this;
    if (discriminator != null) {
      BasicResultMapping mapping = (BasicResultMapping)discriminator.getResultMapping();
      Object value = getPrimitiveResultMappingValue(rs, mapping);
      subMap = discriminator.getSubMap(String.valueOf(value));
      if (subMap == null) {
        subMap = this;
      } else if (subMap != this) {
        subMap = subMap.resolveSubMap(request, rs);
      }
    }
    return subMap;
  }

  /**
   * Setter for a list of the individual ResultMapping objects
   *
   * @param resultMappingList - the list
   */
  public void setResultMappingList(List resultMappingList) {
    if (allowRemapping) {
      this.remappableResultMappings.set((BasicResultMapping[]) resultMappingList.toArray(new BasicResultMapping[resultMappingList.size()]));
    } else {
      this.resultMappings = (BasicResultMapping[]) resultMappingList.toArray(new BasicResultMapping[resultMappingList.size()]);
    }


    Map props = new HashMap();
    props.put("map", this);
    dataExchange = getDelegate().getDataExchangeFactory().getDataExchangeForClass(resultClass);
    dataExchange.initialize(props);
  }

  /**
   * Getter for the number of ResultMapping objects
   *
   * @return - the count
   */
  public int getResultCount() {
    return this.getResultMappings().length;
  }

  /**
   * @param rs
   * @return
   * @throws java.sql.SQLException
   */
  public Object[] getResults(RequestScope request, ResultSet rs)
      throws SQLException {
    ErrorContext errorContext = request.getErrorContext();
    errorContext.setActivity("applying a result map");
    errorContext.setObjectId(this.getId());
    errorContext.setResource(this.getResource());
    errorContext.setMoreInfo("Check the result map.");

    boolean foundData = false;
    Object[] columnValues = new Object[getResultMappings().length];
    for (int i = 0; i < getResultMappings().length; i++) {
      BasicResultMapping mapping = (BasicResultMapping) getResultMappings()[i];
      errorContext.setMoreInfo(mapping.getErrorString());
      if (mapping.getStatementName() != null) {
        if (resultClass == null) {
          throw new SqlMapException("The result class was null when trying to get results for ResultMap named " + getId() + ".");
        } else if (Map.class.isAssignableFrom(resultClass)) {
          columnValues[i] = getNestedSelectMappingValue(request, rs, mapping, Object.class);
        } else if (DomTypeMarker.class.isAssignableFrom(resultClass)) {
          Class javaType = mapping.getJavaType();
          if (javaType == null) {
            javaType = DomTypeMarker.class;
          }
          columnValues[i] = getNestedSelectMappingValue(request, rs, mapping, javaType);
        } else {
          Probe p = ProbeFactory.getProbe(resultClass);
          Class type = p.getPropertyTypeForSetter(resultClass, mapping.getPropertyName());
          columnValues[i] = getNestedSelectMappingValue(request, rs, mapping, type);
        }
      } else if (mapping.getNestedResultMapName() == null) {
        columnValues[i] = getPrimitiveResultMappingValue(rs, mapping);
      }
      foundData = foundData || columnValues[i] != null;
    }

    request.setRowDataFound(foundData);

    return columnValues;
  }

  public Object setResultObjectValues(RequestScope request, Object resultObject, Object[] values) {
    Object ukey = getUniqueKey(values);

    Map uniqueKeys = request.getUniqueKeys(this);

    if (uniqueKeys != null && uniqueKeys.containsKey(ukey)) {
      // Unique key is already known, so get the existing result object and process additional results.
      resultObject = uniqueKeys.get(ukey);
      applyNestedResultMap(request, resultObject, values);
      resultObject = NO_VALUE;
    } else if (ukey == null || uniqueKeys == null || !uniqueKeys.containsKey(ukey)) {
      // Unique key is NOT known, so create a new result object and then process additional results.
      resultObject = dataExchange.setData(request, this, resultObject, values);
      // Lazy init key set, only if we're grouped by something (i.e. ukey != null)
      if (ukey != null) {
        if (uniqueKeys == null) {
          uniqueKeys = new HashMap();
          request.setUniqueKeys(this, uniqueKeys);
        }
        uniqueKeys.put(ukey, resultObject);
      }
      applyNestedResultMap(request, resultObject, values);
    } else {
      // Otherwise, we don't care about these results.
      resultObject = NO_VALUE;
    }

    return resultObject;
  }

  private void applyNestedResultMap(RequestScope request, Object resultObject, Object[] values) {
    if (resultObject != null && resultObject != NO_VALUE) {
      if (nestedResultMappings != null) {
        for (int i = 0, n = nestedResultMappings.size(); i < n; i++) {
          BasicResultMapping resultMapping = (BasicResultMapping) nestedResultMappings.get(i);
          setNestedResultMappingValue(resultMapping, request, resultObject, values);
        }
      }
    }
  }

  protected void setNestedResultMappingValue(BasicResultMapping mapping, RequestScope request, Object resultObject, Object[] values) {
    try {

      String resultMapName = mapping.getNestedResultMapName();
      ResultMap resultMap = getDelegate().getResultMap(resultMapName);
      Class type = mapping.getJavaType();
      String propertyName = mapping.getPropertyName();

      Collection c = (Collection) PROBE.getObject(resultObject, propertyName);

      if (c == null) {
        if (type == null) {
          type = PROBE.getPropertyTypeForSetter(resultObject, propertyName);
        }
        if (type == Collection.class || type == List.class || type == ArrayList.class) {
          c = new ArrayList();
        } else if (type == Set.class || type == HashSet.class) {
          c = new HashSet();
        } else {
          try {
            c = (Collection) type.newInstance();
          } catch (Exception e) {
            throw new SqlMapException("Error instantiating collection property for mapping '" + mapping.getPropertyName() + "'.  Cause: " + e, e);
          }
        }
        PROBE.setObject(resultObject, propertyName, c);
      }

      values = resultMap.getResults(request, request.getResultSet());
      if (request.isRowDataFound()) {
        Object o = resultMap.setResultObjectValues(request, null, values);
        if (o != NO_VALUE) {
          c.add(o);
        }
      }
    } catch (SQLException e) {
      throw new SqlMapException("Error getting nested result map values for '" + mapping.getPropertyName() + "'.  Cause: " + e, e);
    }

  }

  protected Object getNestedSelectMappingValue(RequestScope request, ResultSet rs, BasicResultMapping mapping, Class targetType)
      throws SQLException {
    try {
      TypeHandlerFactory typeHandlerFactory = getDelegate().getTypeHandlerFactory();

      String statementName = mapping.getStatementName();
      ExtendedSqlMapClient client = (ExtendedSqlMapClient) request.getSession().getSqlMapClient();

      MappedStatement mappedStatement = client.getMappedStatement(statementName);
      Class parameterType = mappedStatement.getParameterClass();
      Object parameterObject = null;

      if (parameterType == null) {
        parameterObject = prepareBeanParameterObject(rs, mapping, parameterType);
      } else {
        if (typeHandlerFactory.hasTypeHandler(parameterType)) {
          parameterObject = preparePrimitiveParameterObject(rs, mapping, parameterType);
        } else if (DomTypeMarker.class.isAssignableFrom(parameterType)) {
          parameterObject = prepareDomParameterObject(rs, mapping);
        } else {
          parameterObject = prepareBeanParameterObject(rs, mapping, parameterType);
        }
      }

      Object result = null;
      if (parameterObject != null) {

        Sql sql = mappedStatement.getSql();
        ResultMap resultMap = sql.getResultMap(request, parameterObject);
        Class resultClass = resultMap.getResultClass();

        if (resultClass != null && !DomTypeMarker.class.isAssignableFrom(targetType)) {
          if (DomCollectionTypeMarker.class.isAssignableFrom(resultClass)) {
            targetType = DomCollectionTypeMarker.class;
          } else if (DomTypeMarker.class.isAssignableFrom(resultClass)) {
            targetType = DomTypeMarker.class;
          }
        }

        result = ResultLoader.loadResult(client, statementName, parameterObject, targetType);

        String nullValue = mapping.getNullValue();
        if (result == null && nullValue != null) {
          TypeHandler typeHandler = typeHandlerFactory.getTypeHandler(targetType);
          if (typeHandler != null) {
            result = typeHandler.valueOf(nullValue);
          }
        }
      }
      return result;
    } catch (InstantiationException e) {
      throw new NestedSQLException("Error setting nested bean property.  Cause: " + e, e);
    } catch (IllegalAccessException e) {
      throw new NestedSQLException("Error setting nested bean property.  Cause: " + e, e);
    }

  }

  private Object preparePrimitiveParameterObject(ResultSet rs, BasicResultMapping mapping, Class parameterType) throws SQLException {
    Object parameterObject;
    TypeHandlerFactory typeHandlerFactory = getDelegate().getTypeHandlerFactory();
    TypeHandler th = typeHandlerFactory.getTypeHandler(parameterType);
    parameterObject = th.getResult(rs, mapping.getColumnName());
    return parameterObject;
  }

  private Document newDocument(String root) {
    try {
      Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
      doc.appendChild(doc.createElement(root));
      return doc;
    } catch (ParserConfigurationException e) {
      throw new NestedRuntimeException("Error creating XML document.  Cause: " + e);
    }
  }

  private Object prepareDomParameterObject(ResultSet rs, BasicResultMapping mapping) throws SQLException {
    TypeHandlerFactory typeHandlerFactory = getDelegate().getTypeHandlerFactory();

    Document doc = newDocument("parameter");
    Probe probe = ProbeFactory.getProbe(doc);

    String complexName = mapping.getColumnName();

    TypeHandler stringTypeHandler = typeHandlerFactory.getTypeHandler(String.class);
    if (complexName.indexOf('=') > -1) {
      // old 1.x style multiple params
      StringTokenizer parser = new StringTokenizer(complexName, "{}=, ", false);
      while (parser.hasMoreTokens()) {
        String propName = parser.nextToken();
        String colName = parser.nextToken();
        Object propValue = stringTypeHandler.getResult(rs, colName);
        probe.setObject(doc, propName, propValue.toString());
      }
    } else {
      // single param
      Object propValue = stringTypeHandler.getResult(rs, complexName);
      probe.setObject(doc, "value", propValue.toString());
    }

    return doc;
  }


  private Object prepareBeanParameterObject(ResultSet rs, BasicResultMapping mapping, Class parameterType)
      throws InstantiationException, IllegalAccessException, SQLException {
    TypeHandlerFactory typeHandlerFactory = getDelegate().getTypeHandlerFactory();

    Object parameterObject;
    if (parameterType == null) {
      parameterObject = new HashMap();
    } else {
      parameterObject = Resources.instantiate(parameterType);
    }
    String complexName = mapping.getColumnName();

    if (complexName.indexOf('=') > -1
        || complexName.indexOf(',') > -1) {
      StringTokenizer parser = new StringTokenizer(complexName, "{}=, ", false);
      while (parser.hasMoreTokens()) {
        String propName = parser.nextToken();
        String colName = parser.nextToken();
        Class propType = PROBE.getPropertyTypeForSetter(parameterObject, propName);
        TypeHandler propTypeHandler = typeHandlerFactory.getTypeHandler(propType);
        Object propValue = propTypeHandler.getResult(rs, colName);
        PROBE.setObject(parameterObject, propName, propValue);
      }
    } else {
      // single param
      TypeHandler propTypeHandler = typeHandlerFactory.getTypeHandler(parameterType);
      if (propTypeHandler == null) {
        propTypeHandler = typeHandlerFactory.getUnkownTypeHandler();
      }
      parameterObject = propTypeHandler.getResult(rs, complexName);
    }

    return parameterObject;
  }

  protected Object getPrimitiveResultMappingValue(ResultSet rs, BasicResultMapping mapping) throws SQLException {
    Object value = null;
    TypeHandler typeHandler = mapping.getTypeHandler();
    if (typeHandler != null) {
      String columnName = mapping.getColumnName();
      int columnIndex = mapping.getColumnIndex();
      String nullValue = mapping.getNullValue();
      if (columnName == null) {
        value = typeHandler.getResult(rs, columnIndex);
      } else {
        value = typeHandler.getResult(rs, columnName);
      }
      if (value == null && nullValue != null) {
        value = typeHandler.valueOf(nullValue);
      }
    } else {
      throw new SqlMapException("No type handler could be found to map the property '" + mapping.getPropertyName() + "' to the column '" + mapping.getColumnName() + "'.  One or both of the types, or the combination of types is not supported.");
    }
    return value;
  }


}

