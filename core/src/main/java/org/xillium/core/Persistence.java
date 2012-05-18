package org.xillium.core;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.xillium.data.*;
import org.xillium.data.validation.*;
import org.xillium.data.persistence.*;
import org.xillium.core.*;
import org.springframework.jdbc.datasource.DataSourceUtils;


public class Persistence {
    private final DataSource _dataSource;
    private final Map<String, ParametricStatement> _statements;

    public Persistence(DataSource source) {
        _dataSource = source;
        _statements = new HashMap<String, ParametricStatement>();
    }

    /**
     * Executes an UPDATE/DELETE statement.
     */
    public int executeUpdate(String name, DataObject object) throws SQLException {
        ParametricStatement statement = _statements.get(name);
        if (statement != null) {
            return statement.executeUpdate(DataSourceUtils.getConnection(_dataSource), object);
        } else {
            throw new RuntimeException("ParametricStatement '" + name + "' not found");
        }
    }

    /**
     * Executes an INSERT statement.
     */
    public long[] executeInsert(String name, DataObject object, boolean generatedKeys) throws SQLException {
        ParametricStatement statement = _statements.get(name);
        if (statement != null) {
            return statement.executeInsert(DataSourceUtils.getConnection(_dataSource), object, generatedKeys);
        } else {
            throw new RuntimeException("ParametricStatement '" + name + "' not found");
        }
    }

    /**
     * Executes a SELECT statement and passes the result set to the ResultSetWorker.
     */
    public <T> T executeSelect(String name, DataObject object, ParametricQuery.ResultSetWorker<T> worker) throws Exception {
        ParametricQuery statement = (ParametricQuery)_statements.get(name);
        if (statement != null) {
            return statement.executeSelect(DataSourceUtils.getConnection(_dataSource), object, worker);
        } else {
            throw new RuntimeException("ParametricQuery '" + name + "' not found");
        }
    }

    /**
     * Executes a SELECT statement and returns the result set as a list of objects
     */
    public <T extends DataObject> List<T> getResults(String name, DataObject object) throws Exception {
        ObjectMappedQuery<T> statement = (ObjectMappedQuery<T>)_statements.get(name);
        if (statement != null) {
            return statement.getResults(DataSourceUtils.getConnection(_dataSource), object);
        } else {
            throw new RuntimeException("ObjectMappedQuery '" + name + "' not found");
        }
    }

    /**
     * Executes a SELECT statement and returns the result set as a list of objects
     */
    public <T extends DataObject> Collector<T> getResults(String name, DataObject object, Collector<T> collector) throws Exception {
        ObjectMappedQuery<T> statement = (ObjectMappedQuery<T>)_statements.get(name);
        if (statement != null) {
            return statement.getResults(DataSourceUtils.getConnection(_dataSource), object, collector);
        } else {
            throw new RuntimeException("ObjectMappedQuery '" + name + "' not found");
        }
    }

    public StringBuilder print(StringBuilder sb) {
        return sb.append("Persistence:DataSource=").append(_dataSource.toString());
    }

    Map<String, ParametricStatement> getStatementMap() {
        return _statements;
    }
}
