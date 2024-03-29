package org.xillium.data.persistence.crud;

import java.util.*;
import java.sql.*;
import javassist.*;
import javassist.bytecode.*;
import javassist.bytecode.annotation.MemberValue;
import javassist.bytecode.annotation.IntegerMemberValue;
import org.xillium.base.beans.Strings;
import org.xillium.base.util.Pair;
import org.xillium.data.*;
import org.xillium.data.persistence.*;
import org.xillium.data.persistence.util.MetaDataHelper;


/**
 * A CRUD operation generated from database schema. Operations include
 * <ul>
 * <li><code>CREATE</code> - INSERT data to one or more tables</li>
 * <li><code>RETRIEVE</code> - SELECT a single row from one or more tables located by the primary key</li>
 * <li><code>UPDATE</code> - UPDATE a single row in one or more tables located by the primary key</li>
 * <li><code>DELETE</code> - DELETE a single row in one or more tables located by the primary key</li>
 * <li><code>SEARCH</code> - SELECT rows from a single table by conditions on a variety of columns</li>
 * </ul>
 *
 * <h4>General Features</h4>
 * CREATE, RETRIEVE, UPDATE, DELETE operations on multiple tables associated with ISA relations are supported. SEARCH operation must operate on
 * a single table/view.
 * <p/>
 *
 * <h4>Dominant Tables</h4>
 * In RETRIEVE and SEARCH operations, dominant tables can be specified so that only their columns are selected.
 *
 * <h4>Specified Column List</h4>
 * <p>A CREATE operation can be given a set of columns to exclude from the INSERT statement.</p>
 * <p>An UPDATE operation can target a specified set of columns. If this set of columns is not specified, the statement updates all
 * non-key columns.</p>
 * <p>To a SEARCH operation, a specified set of columns forms the search condition and therefore must be specified.</p>
 *
 * <h4>Column Value Restrictions</h4>
 * <p>It is possible to specify restriction values for specific columns; a value starting with '!' indicates a negative restriction.</p>
 * <ul>
 * <li><code>CREATE</code> - only positive restrictions make sense; negative restriction values are ignored</li>
 * <li><code>RETRIEVE</code>, <code>DELETE</code> - restrictions on key columns appear in the WHERE clause, others are ignored</li>
 * <li><code>UPDATE</code> - restrictions on key columns appear in the WHERE clause, others appear in the SET clause.</li>
 * <li><code>SEARCH</code> - restrictions appear in the WHERE clause</li>
 * </ul>
 *
 * A SEARCH command with n optional arguments will generate 2^n statements to match the incoming request arguments.
 */
public class CrudCommand {
    private static final String STATEMENT_FIELD_NAME = "_STMT";
    private static final char REQUIRED_INDICATOR = '*';
    private static final char DOMINANT_INDICATOR = '*';
    private static final char NEGATIVE_INDICATOR = '!';
    private static final Map<String, Class<? extends DataObject>> _classes = new HashMap<String, Class<? extends DataObject>>();

    private final Operation _oper;
    private final String _name, _desc;
    private final String[] _opts;
    private final Class<? extends DataObject> _type;

    public static enum Operation {
        CREATE,
        RETRIEVE,
        UPDATE,
        DELETE,
        SEARCH
    }

    public static class Action {
        final Operation op;
        final String[] args;
        final boolean[] reqd;
        final String[] cmps;
        final Map<String, String> restriction;
        final String[] opts;

        public Action(Operation op) {
            this.op = op;
            this.args = null;
            this.reqd = null;
            this.cmps = null;
            this.restriction = null;
            this.opts = null;
        }

        public Action(Operation op, Map<String, String> restriction) {
            this.op = op;
            this.restriction = restriction;
            if (op == Operation.SEARCH) {
                this.args = restriction.keySet().toArray(new String[restriction.size()]);
                this.reqd = new boolean[this.args.length];
                this.cmps = new String[this.args.length];
                List<String> opts = analyzeRequiredArgs();
                this.opts = opts.toArray(new String[opts.size()]);
            } else {
                this.args = null;
                this.reqd = null;
                this.cmps = null;
                this.opts = null;
            }
        }

        /**
         * @param args - applies to CREATE, UPDATE, SEARCH. A leading asterisk indicates a required fields for SEARCH operation.
         */
        public Action(Operation op, String[] args) {
            this.op = op;
            this.restriction = null;
            this.args = args;
            this.reqd = new boolean[this.args.length];
            this.cmps = new String[this.args.length];
            List<String> opts = analyzeRequiredArgs();
            this.opts = opts.toArray(new String[opts.size()]);
        }

        /**
         * An Action with both column list (args) and restriction list.
         * </p>
         * The column list applies to UPDATE and SEARCH operations. A leading asterisk before a column name indicates a required fields.
         * <ul>
         * <li>UPDATE - in this case, the columns are used in the SET clause.</li>
         * <li>SEARCH - in this case, the columns are used in the WHERE clause. Trailing symbols after a column name indicate custom comparison operations:
         *     <ul>
         *     <li> <code>&gt;</code>, <code>&gt;=</code>, <code>&lt;</code>, <code>&lt;=</code> - inequality comparisons</li>
         *     <li> '.' suffix - multiple comparison on the same column
         *     </ul>
         *     By default, the comparison operation is the equality test.
         * </ul>
         *
         * @param args - the column list
         * @param restriction - applies to CREATE, UPDATE, SEARCH, DELETE.  On UPDATE, if a restricted column name appears in the column list, it
         *        appears in the SET clause; otherwise it appears in the WHERE clause.
         */
        public Action(Operation op, String[] args, Map<String, String> restriction) {
            this.op = op;
            this.restriction = restriction;
            if (op == Operation.UPDATE || op == Operation.SEARCH) {
                Set<String> set = new HashSet<String>(restriction.keySet());
                for (int i = 0; i < args.length; ++i) set.add(args[i]);
                this.args = set.toArray(new String[set.size()]);
            } else {
                this.args = args;
            }
            this.reqd = new boolean[this.args.length];
            this.cmps = new String[this.args.length];
            List<String> opts = analyzeRequiredArgs();
            this.opts = opts.toArray(new String[opts.size()]);
        }

        public boolean isRequired(String column) {
            if (reqd != null) for (int i = 0; i < reqd.length; ++i) {
                if (args[i].equals(column)) return reqd[i];
            }
            return false;
        }

        public String toString() {
            return op.toString() + " args=" + Arrays.toString(args) + " rstr=" + restriction;
        }

        private List<String> analyzeRequiredArgs() {
            List<String> optionals = new ArrayList<String>();
            for (int i = 0; i < args.length; ++i) {
                if (args[i].endsWith("<") || args[i].endsWith(">")) {
                    cmps[i] = args[i].substring(args[i].length()-1);
                    args[i] = args[i].substring(0, args[i].length()-1);
                } else if (args[i].endsWith("<=") || args[i].endsWith(">=") || args[i].endsWith("<>")) {
                    cmps[i] = args[i].substring(args[i].length()-2);
                    args[i] = args[i].substring(0, args[i].length()-2);
                } else {
                    cmps[i] = "=";
                }

                if (args[i].charAt(0) == REQUIRED_INDICATOR) {
                    args[i] = args[i].substring(1);
                    reqd[i] = true;
                } else {
                    String restr = restriction != null ? restriction.get(args[i]) : null;
                    if (restr == null || restr.charAt(0) == NEGATIVE_INDICATOR) {
                        optionals.add(args[i]);
                    }
                }
            }
            return optionals;
        }
    }

    /**
     * Constructs a CrudCommand.
     *
     * The name of the last table is taken as the name of the model.
     */
    public CrudCommand(Connection connection, String prefix, String tables, Action action) throws Exception {
        String[] names = tables.split(" *, *");
        _oper = action.op;
        _name = Strings.toCamelCase(names[names.length-1], '_');
        _desc = action.toString() + " >> " + tables;
        String cname = className("org.xillium.d.p.c." + prefix, tables, action);
        synchronized (_classes) {
            Class<? extends DataObject> type = _classes.get(cname);
            if (type == null) {
                try {
                    type = modelFromTables(connection, cname, action, names);
                } catch (Exception x) {
                    throw new Exception(tables, x);
                }
                _classes.put(cname, type);
            }
            _type = type;
        }
        _opts = action.opts;
    }

    public Class<? extends DataObject> getRequestType() {
        return _type;
    }

    public Operation getOperation() {
        return _oper;
    }

    public String getName() {
        return _name;
    }

    public String getDescription() {
        return _desc;
    }

    /**
     * For SEARCH operation only, chooses the appropriate statement based on query parameters provided in the binder.
     */
    public int choose(DataBinder binder) {
        int index = 0;
        if (_opts != null) for (int i = 0; i < _opts.length; ++i) {
            String v = binder.get(_opts[i]);
            if (v != null && v.length() > 0) index |= 1 << i;
        }
        return index;
    }

    /**
     * Returns an array of ParametricStatement's that perform the designated CRUD operation on the tables.
     *
     * For RETRIEVE and SEARCH operations the array contains only 1 statement.
     */ 
    public ParametricStatement[] getStatements() {
        try {
            return (ParametricStatement[])_type.getDeclaredField(STATEMENT_FIELD_NAME).get(null);
        } catch (Exception x) {
            throw new RuntimeException("Unexpected CRUD class error", x);
        }
    }

    /**
     * Creates a new Java model class for carrying out the CRUD action on the entity represented by the list of tables.
     */
    @SuppressWarnings({"unchecked", "fallthrough"})
    public static Class<? extends DataObject> modelFromTables(Connection connection, String classname, Action action, String... tablenames) throws Exception {

        /*
            SQL generation

                INSERT INTO T  (  cols ) VALUES ( vals );
                UPDATE      T SET cols      WHERE vals;
                DELETE FROM T               WHERE vals;
                SELECT *     FROM cols      WHERE vals;
        */

        ClassPool pool = ClassPool.getDefault();
        // this line is necessary for web applications (web container class loader in play)
        pool.appendClassPath(new LoaderClassPath(org.xillium.data.DataObject.class.getClassLoader()));

        CtClass cc = pool.makeClass(classname);
        cc.addInterface(pool.getCtClass("org.xillium.data.DataObject"));
        ConstPool cp = cc.getClassFile().getConstPool();

        List<String> fragments = new ArrayList<String>();
        Set<String> unique = new HashSet<String>();

/*SQL*/ StringBuilder
            cols = new StringBuilder(),    // CREATE: COLUMNS, RETRIEVE: TABLES, UPDATE: SET CLAUSES, DELETE: (not used), SEARCH: TABLES
            vals = new StringBuilder(),    // CREATE: VALUES,  RETRIEVE: COND'S, UPDATE: COND'S,      DELETE: COND'S,     SEARCH: COND'S
            flds = new StringBuilder();

        // for SEARCH only
        Map<String, List<Pair<Integer, Integer>>> voptional = new HashMap<String, List<Pair<Integer, Integer>>>();
        Map<String, List<Pair<Integer, Integer>>> foptional = new HashMap<String, List<Pair<Integer, Integer>>>();
        Map<String, String> nametrans = new HashMap<String, String>();

        DatabaseMetaData meta = connection.getMetaData();
        String schema = meta.getUserName();
        List<String> dominant = new ArrayList<String>();

        for (int i = 0; i < tablenames.length; ++i) {
            // Dominant tables are recognized and maintained in 'dominant' list
            if (tablenames[i].charAt(0) == DOMINANT_INDICATOR) {
                tablenames[i] = tablenames[i].substring(1);
                dominant.add(tablenames[i]);
            }

            PreparedStatement stmt = connection.prepareStatement("SELECT * FROM " + tablenames[i]);
            ResultSetMetaData rsmeta = stmt.getMetaData();
            Map<String, Integer> colref = new HashMap<String, Integer>();
            for (int j = 1, jj = rsmeta.getColumnCount(); j <= jj; ++j) {
                colref.put(rsmeta.getColumnLabel(j), new Integer(j));
            }

            Set<String> primaryKeys = new HashSet<String>();
            ResultSet keys = meta.getPrimaryKeys(connection.getCatalog(), schema, tablenames[i]);
            while (keys.next()) {
                primaryKeys.add(keys.getString(PKEY_COLUMN));
            }
            keys.close();

            // RETRIEVE is not compatible with tables without a primary key
            if (primaryKeys.isEmpty() && action.op == Operation.RETRIEVE) {
                throw new RuntimeException("Primary key expected for RETRIEVE command, but missing on table " + tablenames[i]);
            }

            // ISA keys and table join conditions
            Set<String> isaKeys = new HashSet<String>();
            if (i > 0) {
                keys = meta.getImportedKeys(connection.getCatalog(), schema, tablenames[i]);
                while (keys.next()) {
                    String jointable = null;
                    for (int j = 0; j < i; ++j) {
                        if (keys.getString(FKEY_REFERENCED_TABLE).equals(tablenames[j])) {
                            jointable = tablenames[j];
                            break;
                        }
                    }
                    if (jointable != null) {
                        String column = keys.getString(FKEY_REFERENCING_COLUMN);
                        isaKeys.add(column);
                        if (action.op == Operation.RETRIEVE || action.op == Operation.SEARCH) {
    /*SQL*/                 vals.append(tablenames[i]).append('.').append(column).append('=').append(jointable).append('.').append(column).append(" AND ");
                        }
                    }
                }
                keys.close();
            }

            String alias = ((action.op == Operation.RETRIEVE || action.op == Operation.SEARCH) && tablenames.length > 1) ? tablenames[i]+'.' : "";

            if (action.op != Operation.RETRIEVE && action.op != Operation.SEARCH) {
                cols.setLength(0);
                vals.setLength(0);
                flds.setLength(0);
            } else {
    /*SQL*/     if (cols.length() > 0) cols.append(',');
    /*SQL*/     cols.append(tablenames[i]);
            }

            // Go through all columns in action.args for UPDATE and SEARCH operations
            Set<String> requested = new HashSet<String>();
            Set<String> required = new HashSet<String>();
            if (action.op == Operation.UPDATE) {
                // UPDATE: the elements in the SET clause => cols
                for (String column: calcUpdateColumns(action.args, colref, primaryKeys)) {
                    // skip all key columns, which might be legally included through the restriction list
                    if (primaryKeys.contains(column)) continue;
                    // ... and SET others
                    Integer idx = colref.get(column);
                    if (idx != null) {
    /*SQL*/             if (cols.length() > 0) cols.append(',');
    /*SQL*/             String restriction = action.restriction == null ? null : action.restriction.get(column);
                        if (restriction == null) {
                            boolean reqd = action.isRequired(column);
                            if (reqd) {
    /*SQL*/                     cols.append(column).append("=?");
                            } else {
    /*SQL*/                     cols.append(column).append("=COALESCE(?,").append(column).append(')');
                            }
    /*SQL*/                 if (flds.length() > 0) flds.append(',');
                            flds.append(fieldName(tablenames[i], column)).append(':').append(rsmeta.getColumnType(idx.intValue()));
                            requested.add(column);
                            if (reqd) required.add(column);
                        } else {
    /*SQL*/                 cols.append(column).append('=').append(restriction);
                        }
                    }
                }
            } else if (action.op == Operation.SEARCH && action.args != null) {
                // SEARCH: the elements in the WHERE clause => vals
                for (int c = 0; c < action.args.length; ++c) {
                    // skip all ISA columns, which might be legally included through the restriction list
                    if (isaKeys.contains(action.args[c])) continue;
                    // else ...
                    Integer idx = colref.get(action.args[c]);
                    if (idx != null) {
    /*SQL*/             String restriction = action.restriction == null ? null : action.restriction.get(action.args[c]);
    /*SQL*/             if (restriction == null || restriction.charAt(0) == NEGATIVE_INDICATOR) {
                            if (restriction != null) {
    /*SQL*/                     vals.append(tablenames[i]).append('.').append(action.args[c]).append("<>").append(restriction.substring(1)).append(" AND ");
                            }
                            int vstart = vals.length(), fstart = flds.length();
    /*SQL*/                 vals.append(tablenames[i]).append('.').append(action.args[c]).append(action.cmps[c]).append("? AND ");
                            flds.append(fieldName(tablenames[i], action.args[c])).append(':').append(rsmeta.getColumnType(idx.intValue())).append(',');
                            if (!action.reqd[c]) {
                                traceOptional(voptional, action.args[c], vstart, vals.length());
                                traceOptional(foptional, action.args[c], fstart, flds.length());
                            }
                            requested.add(action.args[c]);
                            if (action.reqd[c]) required.add(action.args[c]);
                        } else {
    /*SQL*/                 vals.append(tablenames[i]).append('.').append(action.args[c]).append('=').append(restriction).append(" AND ");
                        }
                    }
                }
            }

            ResultSet columns = meta.getColumns(connection.getCatalog(), schema, tablenames[i], "%");
    columns:while (columns.next()) {
                String name = columns.getString(COLUMN_NAME), fname = fieldName(tablenames[i], name);
                int idx = colref.get(name).intValue();

                if ((action.op == Operation.RETRIEVE || action.op == Operation.DELETE) && !primaryKeys.contains(name)) {
                    continue;
                } else if (action.op == Operation.UPDATE && !requested.contains(name) && !primaryKeys.contains(name)) {
                    continue;
                } else if (action.op == Operation.SEARCH && !requested.contains(name)) {
                    continue;
                }

    /*SQL*/     String restriction = action.restriction == null ? null : action.restriction.get(name);
                switch (action.op) {
                case CREATE:
                    if (action.args != null) for (int j = 0; j < action.args.length; ++j) {
                        if (action.args[j].equals(name)) continue columns;
                    }
    /*SQL*/         if (cols.length() > 0) {
    /*SQL*/             cols.append(',');
    /*SQL*/             vals.append(',');
    /*SQL*/         }
    /*SQL*/         cols.append(name);
    /*SQL*/         if (restriction == null || restriction.charAt(0) == NEGATIVE_INDICATOR) {
    /*SQL*/             vals.append('?');
    /*SQL*/             if (flds.length() > 0) flds.append(',');
                        flds.append(fname).append(':').append(rsmeta.getColumnType(idx));
                    } else {
    /*SQL*/             vals.append(restriction);
                    }
                    break;
                case RETRIEVE:
                    if (i > 0) {
                        // NOTE: ISA relation dictates that sub-tables' primary key == super-table's primary key
                        // therefore the join condition generated above is sufficient already
                        break;
                    }
                    // fall through for the super-table
                case DELETE:
                    // only primary key columns
                    generateCondition(restriction, vals, flds, alias + name, fname, rsmeta.getColumnType(idx));
                    break;
                case UPDATE:
                    // only primary key & updating columns
                    if (primaryKeys.contains(name)) {
                        generateCondition(restriction, vals, flds, name, fname, rsmeta.getColumnType(idx));
                    }
                    break;
                case SEARCH:
                    // file optionals lists under field names
                    nametrans.put(name, fname);
                    voptional.put(fname, voptional.get(name));
                    foptional.put(fname, foptional.get(name));
                    break;
                }

                if ((restriction != null && restriction.charAt(0) != NEGATIVE_INDICATOR) || isaKeys.contains(name)) {
                    continue;
                } else if (unique.contains(name)) {
                    continue;
                    //throw new RuntimeException("Duplicate column in ISA relationship detected " + tablenames[i] + ':' + name);
                } else {
                    unique.add(name);
                }

                CtField field = new CtField(pool.getCtClass(MetaDataHelper.getClassName(rsmeta, idx)), fname, cc);
                field.setModifiers(java.lang.reflect.Modifier.PUBLIC);
                AnnotationsAttribute attr = new AnnotationsAttribute(cp, AnnotationsAttribute.visibleTag);

                if (required.contains(name)) {
                    addAnnotation(attr, cp, "org.xillium.data.validation.required");
                } else if (columns.getInt(IS_NULLABLE) == DatabaseMetaData.attributeNoNulls) {
                    if ((action.op != Operation.UPDATE || primaryKeys.contains(name)) && action.op != Operation.SEARCH) {
                        addAnnotation(attr, cp, "org.xillium.data.validation.required");
                    }
                }

                if (rsmeta.getPrecision(idx) != 0) {
                    addAnnotation(attr, cp, "org.xillium.data.validation.size", "value", new IntegerMemberValue(cp, rsmeta.getPrecision(idx)));
                }

                field.getFieldInfo().addAttribute(attr);
                cc.addField(field);
            }
            columns.close();
            stmt.close();

            switch (action.op) {
            case CREATE:
                fragments.add("org.xillium.data.persistence.ParametricStatement");
                fragments.add(flds.toString());
                fragments.add("INSERT INTO " + tablenames[i] + '(' + cols.toString() + ") VALUES(" + vals.toString() + ')');
                fragments.add(Strings.toCamelCase(tablenames[i], '_'));
                break;
            case UPDATE:
                if (cols.length() > 0) {
                    fragments.add("org.xillium.data.persistence.ParametricStatement");
                    fragments.add(flds.toString());
                    fragments.add("UPDATE " + tablenames[i] + " SET " + cols.toString() + " WHERE " + vals.toString().replaceAll(" AND *$", ""));
                    fragments.add(Strings.toCamelCase(tablenames[i], '_'));
                }
                break;
            case DELETE:
                fragments.add("org.xillium.data.persistence.ParametricStatement");
                fragments.add(flds.toString());
                fragments.add("DELETE FROM " + tablenames[i] + " WHERE " + vals.toString().replaceAll(" AND *$", ""));
                fragments.add(Strings.toCamelCase(tablenames[i], '_'));
                break;
            case RETRIEVE:
            case SEARCH:
                break;
            }
        }

        if (action.op == Operation.RETRIEVE) {
            fragments.add("org.xillium.data.persistence.ParametricQuery");
            fragments.add(flds.toString());
            fragments.add("SELECT " + selectTarget(dominant) + " FROM " + cols + " WHERE " + vals.toString().replaceAll(" AND *$", ""));
            fragments.add("");
        } else if (action.op == Operation.SEARCH) {
            if (action.opts != null) {
                for (int i = 0; i < action.opts.length; ++i) {
                    action.opts[i] = nametrans.get(action.opts[i]);
                }
                int count = 1 << action.opts.length;

                String vtext = vals.toString(), ftext = flds.toString();
                for (int i = 0; i < count; ++i) {
                    char[] vchars = vtext.toCharArray(), fchars = ftext.toCharArray();
                    for (int j = 0; j < action.opts.length; ++j) {
                        List<Pair<Integer, Integer>> vlist = voptional.get(action.opts[j]);
                        List<Pair<Integer, Integer>> flist = foptional.get(action.opts[j]);
                        if (vlist == null || flist == null) {
                            throw new RuntimeException("Column{"+action.opts[j]+"}NotInRelevantTables");
                        }
                        if ((i & (1 << j)) == 0) {
                            for (Pair<Integer, Integer> part: vlist) Arrays.fill(vchars, part.first, part.second, ' ');
                            for (Pair<Integer, Integer> part: flist) Arrays.fill(fchars, part.first, part.second, ' ');
                        }
                    }

                    fragments.add("org.xillium.data.persistence.ParametricQuery");
                    fragments.add(new String(fchars).replaceAll("\\s+", " "));
                    String vals0 = new String(vchars).replaceAll("\\s+", " ").replaceAll(" AND *$", "").trim();
                    if (vals0.length() > 0) {
                        fragments.add("SELECT " + selectTarget(dominant) + " FROM " + cols + " WHERE " + vals0);
                    } else {
                        fragments.add("SELECT " + selectTarget(dominant) + " FROM " + cols);
                    }
                    fragments.add("");
                }
            } else {
                fragments.add("org.xillium.data.persistence.ParametricQuery");
                fragments.add(flds.toString());
                if (vals.length() > 0) {
                    fragments.add("SELECT " + selectTarget(dominant) + " FROM " + cols + " WHERE " + vals.toString().replaceAll(" AND *$", ""));
                } else {
                    fragments.add("SELECT " + selectTarget(dominant) + " FROM " + cols);
                }
                fragments.add("");
            }
        }

        CtField field = new CtField(pool.getCtClass("org.xillium.data.persistence.ParametricStatement[]"), STATEMENT_FIELD_NAME, cc);
        field.setModifiers(java.lang.reflect.Modifier.PUBLIC | java.lang.reflect.Modifier.STATIC | java.lang.reflect.Modifier.FINAL);
        cc.addField(field, CtField.Initializer.byCallWithParams(
            pool.getCtClass(CrudCommand.class.getName()), "buildStatements", fragments.toArray(new String[fragments.size()])
        ));

        return (Class<? extends DataObject>)cc.toClass(CrudCommand.class.getClassLoader(), CrudCommand.class.getProtectionDomain());
    }

    public static ParametricStatement[] buildStatements(String[] args) throws Exception {
        ParametricStatement[] stmts = new ParametricStatement[args.length/4];
        for (int i = 0; i < stmts.length; ++i) {
            stmts[i] = ((ParametricStatement)Class.forName(args[i*4+0]).getConstructor(String.class).newInstance(args[i*4+1])).set(args[i*4+2]);
            stmts[i].setTag(args[i*4+3]);
        }
        return stmts;
    }

    private static final int COLUMN_NAME = 4;
    //private static final int COLUMN_TYPE = 5;    // java.sql.Types.#
    private static final int COLUMN_SIZE = 7;
    private static final int IS_NULLABLE = 11;
    private static final int PKEY_COLUMN = 4;
    private static final int FKEY_REFERENCED_TABLE = 3;
    //private static final int FKEY_REFERENCED_COLUMN = 4;
    private static final int FKEY_REFERENCING_COLUMN = 8;

    private static void generateCondition(String restriction, StringBuilder vals, StringBuilder flds, String name, String fname, int ftype) {
/*SQL*/ vals.append(name);
        if (restriction == null || restriction.charAt(0) == NEGATIVE_INDICATOR) {
/*SQL*/     vals.append('=').append('?');
            if (flds.length() > 0) flds.append(',');
            flds.append(fname).append(':').append(ftype);
        }
        if (restriction != null) {
            if (restriction.charAt(0) == NEGATIVE_INDICATOR) {
/*SQL*/         vals.append(" AND ").append(name).append("<>").append(restriction.substring(1));
            } else {
/*SQL*/         vals.append('=').append(restriction);
            }
        }
        vals.append(" AND ");
    }

    private static String[] calcUpdateColumns(String[] columns, Map<String, Integer> colref, Set<String> keys) {
        if (columns != null) {
            return columns;
        } else {
            List<String> col = new ArrayList<String>();
            for (String candidate: colref.keySet()) {
                if (!keys.contains(candidate)) col.add(candidate);
            }
            return col.toArray(new String[col.size()]);
        }
    }

    private static void addAnnotation(AnnotationsAttribute attr, ConstPool cp, String aclass) {
        javassist.bytecode.annotation.Annotation annotation = new javassist.bytecode.annotation.Annotation(aclass, cp);
        attr.addAnnotation(annotation);
    }

    private static void addAnnotation(AnnotationsAttribute attr, ConstPool cp, String aclass, String attribute, MemberValue value) {
        javassist.bytecode.annotation.Annotation annotation = new javassist.bytecode.annotation.Annotation(aclass, cp);
        annotation.addMemberValue(attribute, value);
        attr.addAnnotation(annotation);
    }

    private static String className(String pkg, String tables, Action action) {
        StringBuilder sb = new StringBuilder(action.op.toString());
        if (action.args != null) for (int i = 0; i < action.args.length; ++i) {
            sb.append(action.args[i]).append(action.cmps[i]).append(action.reqd[i]);
        }
        if (action.restriction != null) for (String key: action.restriction.keySet()) {
            sb.append(key);
            String value = action.restriction.get(key);
            if (value != null) {
                sb.append(value);
            }
        }
        int hash = sb.toString().hashCode();

        sb.setLength(0);
        return sb.append(pkg).append('.').append(Strings.toCamelCase(action.op.toString(), '_'))
                                         .append(Strings.toCamelCase(tables.replaceAll(" *, *", "_").replaceAll("\\*", ""), '_'))
                                         .append(Integer.toHexString(hash)).toString();
    }

    private static String fieldName(String table, String column) {
        Map<String, String> alias = CrudConfiguration.aliases.get(table);
        if (alias != null) {
            String name = alias.get(column);
            return (name != null) ? name : Strings.toLowerCamelCase(column, '_');
        } else {
            return Strings.toLowerCamelCase(column, '_');
        }
    }

    private static String selectTarget(List<String> tables) {
        if (tables.size() == 0) {
            return "*";
        } else {
            StringBuilder sb = new StringBuilder();
            for (String table: tables) {
                sb.append(table).append(".*,");
            }
            sb.setLength(sb.length() - 1);
            return sb.toString();
        }
    }

    private static void traceOptional(Map<String, List<Pair<Integer, Integer>>> optional, String column, int start, int end) {
        List<Pair<Integer, Integer>> list = optional.get(column);
        if (list == null) {
            optional.put(column, list = new ArrayList<Pair<Integer, Integer>>());
        }
        list.add(new Pair<Integer, Integer>(start, end));
    }
}

