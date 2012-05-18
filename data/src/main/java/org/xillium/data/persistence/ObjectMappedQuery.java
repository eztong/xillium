package org.xillium.data.persistence;

import org.xillium.base.beans.Beans;
import org.xillium.data.*;
//import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.lang.reflect.Field;


/**
 * A prepared SQL statement with named parameters.
 */
public class ObjectMappedQuery<T extends DataObject> extends ParametricQuery {
    private static class Column2Field {
        Field field;
        int index;

        Column2Field(int index, Field field) {
            this.index = index;
            this.field = field;
        }
    }

	private class ResultSetMapper implements ParametricQuery.ResultSetWorker<Collector<T>> {
        private final Collector<T> _collector;

        public ResultSetMapper(Collector<T> c) {
            _collector = c;
        }

		public Collector<T> process(ResultSet rs) throws SQLException, InstantiationException, IllegalAccessException {
			try {
                if (_list == null) {
                    synchronized(ObjectMappedQuery.this) {
                        if (_list == null) {
                            List<Column2Field> list = new ArrayList<Column2Field>();
                            ResultSetMetaData meta = rs.getMetaData();
                            for (int i = 1, ii = meta.getColumnCount()+1; i < ii; ++i) {
                                try {
                                    String name = Beans.toLowerCamelCase(meta.getColumnName(i), '_');
                                    list.add(new Column2Field(i, _type.getField(name)));
                                } catch (NoSuchFieldException x) {
                                    // ignored
                                }
                            }
                            _list = list;
                        }
                    }
				}

				//List<T> rows = new ArrayList<T>();
				while (rs.next()) {
					T object = _type.newInstance();
					for (Column2Field c2f: _list) {
						c2f.field.setAccessible(true);
                        Object value = rs.getObject(c2f.index);
                        try {
                            c2f.field.set(object, value);
                        } catch (IllegalArgumentException x) {
                            // size of "value" bigger than that of "field"?
                            if (value instanceof Number) {
                                try {
									Number number = (Number)value;
                                    Class ftype = c2f.field.getType();
                                    if (Double.class.isAssignableFrom(ftype)) {
                                        c2f.field.set(object, number.doubleValue());
                                    } else if (Float.class.isAssignableFrom(ftype)) {
                                        c2f.field.set(object, number.floatValue());
                                    } else if (Long.class.isAssignableFrom(ftype)) {
                                        c2f.field.set(object, number.longValue());
                                    } else if (Integer.class.isAssignableFrom(ftype)) {
                                        c2f.field.set(object, number.intValue());
                                    } else if (Short.class.isAssignableFrom(ftype)) {
                                        c2f.field.set(object, number.shortValue());
                                    } else {
                                        c2f.field.set(object, number.byteValue());
                                    }
                                } catch (Throwable t) {
                                    throw x;
                                }
                            } else if (value instanceof java.sql.Timestamp) {
                                try {
                                    c2f.field.set(object, new java.sql.Date(((java.sql.Timestamp)value).getTime()));
                                } catch (Throwable t) {
                                    throw x;
                                }
                            } else {
                                throw x;
                            }
                        }
					}
					//rows.add(object);
                    _collector.add(object);
				}
				//return rows;
                return _collector;
			} finally {
				rs.close();
			}
		}
	}

    private static class ListCollector<T> extends ArrayList<T> implements Collector<T> {}
	private final Class<T> _type;
	private final ResultSetMapper _lister = new ResultSetMapper(new ListCollector<T>());
    private volatile List<Column2Field> _list; // lazily initialized with double checked locking

    public ObjectMappedQuery(Param[] parameters, String sql, Class<T> type) throws IllegalArgumentException {
		super(parameters, sql);
		_type = type;
    }

    public ObjectMappedQuery(String parameters,  String classname) throws IllegalArgumentException {
        super(parameters);
		try {
            _type = (Class<T>)Class.forName(classname);
        } catch (ClassNotFoundException x) {
            throw new IllegalArgumentException(x);
        }
    }

	/**
	 * Execute the query and returns the results as a list of objects.
	 */
    public List<T> getResults(Connection conn, DataObject object) throws Exception {
		return (List<T>)super.executeSelect(conn, object, _lister);
    }

	/**
	 * Execute the query and passes the results to a Collector.
	 */
    public Collector<T> getResults(Connection conn, DataObject object, Collector<T> collector) throws Exception {
		return super.executeSelect(conn, object, new ResultSetMapper(collector));
    }
}
