/**
 * Copyright (c) 2009-2015, Data Geekery GmbH (http://www.datageekery.com)
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Other licenses:
 * -----------------------------------------------------------------------------
 * Commercial licenses for this work are available. These replace the above
 * ASL 2.0 and offer limited warranties, support, maintenance, and commercial
 * database integrations.
 *
 * For more information, please visit: http://www.jooq.org/licenses
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package org.jooq.impl;

import static java.util.Arrays.asList;
// ...
// ...
// ...
// ...
import static org.jooq.conf.RenderNameStyle.LOWER;
import static org.jooq.conf.RenderNameStyle.UPPER;
import static org.jooq.impl.DSL.select;
// ...
import static org.jooq.impl.Utils.fieldArray;
import static org.jooq.impl.Utils.unqualify;
import static org.jooq.util.sqlite.SQLiteDSL.rowid;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.jooq.Configuration;
import org.jooq.Context;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.Field;
import org.jooq.Identity;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.conf.RenderNameStyle;
import org.jooq.tools.jdbc.JDBCUtils;

/**
 * @author Lukas Eder
 */
abstract class AbstractDMLQuery<R extends Record> extends AbstractQuery {

    /**
     * Generated UID
     */
    private static final long     serialVersionUID = -7438014075226919192L;

    final WithImpl                with;
    final Table<R>                table;
    final QueryPartList<Field<?>> returning;
    Result<R>                     returned;

    AbstractDMLQuery(Configuration configuration, WithImpl with, Table<R> table) {
        super(configuration);

        this.with = with;
        this.table = table;
        this.returning = new QueryPartList<Field<?>>();
    }

    // @Override
    public final void setReturning() {
        setReturning(table.fields());
    }

    // @Override
    public final void setReturning(Identity<R, ?> identity) {
        if (identity != null) {
            setReturning(identity.getField());
        }
    }

    // @Override
    public final void setReturning(Field<?>... fields) {
        setReturning(Arrays.asList(fields));
    }

    // @Override
    public final void setReturning(Collection<? extends Field<?>> fields) {
        returning.clear();
        returning.addAll(fields);
    }

    // @Override
    public final R getReturnedRecord() {
        if (getReturnedRecords().size() == 0) {
            return null;
        }

        return getReturnedRecords().get(0);
    }

    // @Override
    public final Result<R> getReturnedRecords() {
        if (returned == null) {
            returned = new ResultImpl<R>(configuration(), returning);
        }

        return returned;
    }

    @Override
    public final void accept(Context<?> ctx) {
        if (with != null)
            ctx.visit(with).formatSeparator();

        /* [pro] xx
        xx xxxxxxxxxxxxx xx xxx
                xx xxxxxxxxxxxxxxxxxxxx
                xx xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx xx xxxxx x
            xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx xxxxxx
            xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx xxx xxxxxxxxxxxxxxxxxxx
            xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx xxxxxx
        x
        xxxx
        xx [/pro] */
        {
            accept0(ctx);
        }
    }

    abstract void accept0(Context<?> ctx);

    final void toSQLReturning(Context<?> ctx) {
        if (!returning.isEmpty()) {
            switch (ctx.family()) {
                case FIREBIRD:
                case POSTGRES:
                    ctx.formatSeparator()
                       .keyword("returning")
                       .sql(' ')
                       .visit(returning);
                    break;

                default:
                    // Other dialects don't render a RETURNING clause, but
                    // use JDBC's Statement.RETURN_GENERATED_KEYS mode instead
                    break;
            }
        }
    }


    @Override
    protected final void prepare(ExecuteContext ctx) throws SQLException {
        Connection connection = ctx.connection();

        /* [pro] xx
        xx xxxx xx xxxxx xxxxxx xxx xxxxxx xxx xxxxxxxxx xxxx xx xxxxxx
        xx xxxxxxxxx xxxx xx xxxxxx xxxx xxxxx xx xxxxxx xxxxxxxxxx xxxxxxxxxx
        xx xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx xx xxxxxxxxxxxxxxx x
            xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
            xxxxxxx
        x

        xx xxxxxx xxxxxxxxx xxxxxxxxx xx xx xxxxxx xxxxxx xx xxxxxxxx
        xxxx xx [/pro] */if (returning.isEmpty()) {
            super.prepare(ctx);
            return;
        }

        /* [pro] xx
        xx xxxxxx xxxxxx xxxxx xxxx xxxxxxx xxxxxxxx xxxxxxxxx xxxx
        xxxx xx xxxxxxxxxxxxx xxxxxxxxx xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx x
            xxxxxxxxxxxxxxxxxxx
            xxxxxxx
        x
        xx [/pro] */

        // Values should be returned from the INSERT
        else {
            switch (ctx.family()) {

                /* [pro] xx
                xxxx xxxxxxx
                xx xxxxxxx xxx xxxxx xxx xxxxxx xx xxxx xxxxx xxxxx xxxxxxx xxx xxxxxx
                xxxx xxxx
                xx xxxxxx xxxx xxxxxx xxxxxxxxxx xxxxx xxx xxxxxx
                xxxx xxxxxxx
                xx [/pro] */

                // Postgres uses the RETURNING clause in SQL
                case FIREBIRD:
                case POSTGRES:
                // SQLite will select last_insert_rowid() after the INSER
                case SQLITE:
                case CUBRID:

                    super.prepare(ctx);
                    return;

                // Some dialects can only return AUTO_INCREMENT values
                // Other values have to be fetched in a second step
                // [#1260] TODO CUBRID supports this, but there's a JDBC bug
                /* [pro] xx
                xxxx xxxx
                xxxx xxxxxxxxx
                xxxx xxxxxxx
                xxxx xxxxxxxxxx
                xxxx xxxxxxxxx
                xx [/pro] */
                case DERBY:
                case H2:
                case MARIADB:
                case MYSQL:
                    ctx.statement(connection.prepareStatement(ctx.sql(), Statement.RETURN_GENERATED_KEYS));
                    return;

                // The default is to return all requested fields directly
                /* [pro] xx
                xxxx xxxxxxx
                xx [/pro] */
                case HSQLDB:
                default: {
                    List<String> names = new ArrayList<String>();
                    RenderNameStyle style = configuration().settings().getRenderNameStyle();

                    for (Field<?> field : returning) {

                        // [#2845] Field names should be passed to JDBC in the case
                        // imposed by the user. For instance, if the user uses
                        // PostgreSQL generated case-insensitive Fields (default to lower case)
                        // and wants to query HSQLDB (default to upper case), they may choose
                        // to overwrite casing using RenderKeywordStyle.
                        if (style == UPPER)
                            names.add(field.getName().toUpperCase());
                        else if (style == LOWER)
                            names.add(field.getName().toLowerCase());
                        else
                            names.add(field.getName());
                    }

                    ctx.statement(connection.prepareStatement(ctx.sql(), names.toArray(new String[names.size()])));
                    return;
                }
            }
        }
    }

    @Override
    protected final int execute(ExecuteContext ctx, ExecuteListener listener) throws SQLException {
        if (returning.isEmpty()) {
            return super.execute(ctx, listener);
        }
        /* [pro] xx
        xx xxxxxx xxxxxx xxxxx xxxx xxxxxxx xxxxxxxx xxxxxxxxx xxxx
        xxxx xx xxxxxxxxxxxxx xxxxxxxxx xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx x
            xxxxxx xxxxxxxxxxxxxxxxxx xxxxxxxxxx
        x
        xx [/pro] */
        else {
            int result = 1;
            ResultSet rs;
            switch (ctx.family()) {

                // SQLite can select _rowid_ after the insert
                case SQLITE: {
                    listener.executeStart(ctx);
                    result = ctx.statement().executeUpdate();
                    ctx.rows(result);
                    listener.executeEnd(ctx);

                    DSLContext create = DSL.using(ctx.configuration());
                    returned =
                    create.select(returning)
                          .from(table)
                          .where(rowid().equal(rowid().getDataType().convert(create.lastID())))
                          .fetchInto(table);

                    return result;
                }

                /* [pro] xx
                xxxx xxxxxxx

                xx xxxxxx xxx xxxxxx xxxxxxxxxx xxxxx xxx xxxxxx
                xx xxxx xxxxxx xxx xxxxx xxxx xxxxx xx x xxxxxx xxxxxx xxxx
                xx xxxxxxxxx xxxx xxxxx xxxx xxxx xxxxxxx xxx xxxx xxxx xx xxxx
                xx xxxx xxxx xxxxxx xx xxxx xxx xxxxxx xxx xxxxxxxxxxxx
                xxxx xxxxxxx
                xx [/pro] */

                case CUBRID: {
                    listener.executeStart(ctx);
                    result = ctx.statement().executeUpdate();
                    ctx.rows(result);
                    listener.executeEnd(ctx);

                    selectReturning(ctx.configuration(), create(ctx.configuration()).lastID());
                    return result;
                }

                // Some dialects can only retrieve "identity" (AUTO_INCREMENT) values
                // Additional values have to be fetched explicitly
                // [#1260] TODO CUBRID supports this, but there's a JDBC bug
                /* [pro] xx
                xxxx xxxx
                xxxx xxxxxxxxx
                xxxx xxxxxxx
                xxxx xxxxxxxxxx
                xxxx xxxxxxxxx
                xx [/pro] */
                case DERBY:
                case H2:
                case MARIADB:
                case MYSQL: {
                    listener.executeStart(ctx);
                    result = ctx.statement().executeUpdate();
                    ctx.rows(result);
                    listener.executeEnd(ctx);

                    rs = ctx.statement().getGeneratedKeys();

                    try {
                        List<Object> list = new ArrayList<Object>();

                        // Some JDBC drivers seem to illegally return null
                        // from getGeneratedKeys() sometimes
                        if (rs != null) {
                            while (rs.next()) {
                                list.add(rs.getObject(1));
                            }
                        }

                        selectReturning(ctx.configuration(), list.toArray());
                        return result;
                    }
                    finally {
                        JDBCUtils.safeClose(rs);
                    }
                }

                /* [pro] xx
                xx xxxxxxx xxx xxxxx xxx xxxxxx xx xxxx xxxxx xxxxx xxxxxxx xxx xxxxxx
                xxxx xxxx
                xx [/pro] */

                // Firebird and Postgres can execute the INSERT .. RETURNING
                // clause like a select clause. JDBC support is not implemented
                // in the Postgres JDBC driver
                case FIREBIRD:
                case POSTGRES: {
                    listener.executeStart(ctx);
                    rs = ctx.statement().executeQuery();
                    listener.executeEnd(ctx);
                    break;
                }

                // These dialects have full JDBC support
                /* [pro] xx
                xxxx xxxxxxx
                xx [/pro] */
                case HSQLDB:
                default: {
                    listener.executeStart(ctx);
                    result = ctx.statement().executeUpdate();
                    ctx.rows(result);
                    listener.executeEnd(ctx);

                    rs = ctx.statement().getGeneratedKeys();
                    break;
                }
            }

            ExecuteContext ctx2 = new DefaultExecuteContext(ctx.configuration());
            ExecuteListener listener2 = new ExecuteListeners(ctx2);

            ctx2.resultSet(rs);
            returned = new CursorImpl<R>(ctx2, listener2, fieldArray(returning), null, false, true).fetch();

            // [#3682] Plain SQL tables do not have any fields
            if (table.fields().length > 0)
                returned = returned.into(table);

            return result;
        }
    }

    /**
     * Get the returning record in those dialects that do not support fetching
     * arbitrary fields from JDBC's {@link Statement#getGeneratedKeys()} method.
     */
    @SuppressWarnings("unchecked")
    private final void selectReturning(Configuration configuration, Object... values) {
        if (values != null && values.length > 0) {

            // This shouldn't be null, as relevant dialects should
            // return empty generated keys ResultSet
            if (table.getIdentity() != null) {
                final Field<Object> field = (Field<Object>) table.getIdentity().getField();
                Object[] ids = new Object[values.length];
                for (int i = 0; i < values.length; i++) {
                    ids[i] = field.getDataType().convert(values[i]);
                }

                // Only the IDENTITY value was requested. No need for an
                // additional query
                if (returning.size() == 1 && new Fields<Record>(returning).field(field) != null) {
                    for (final Object id : ids) {
                        getReturnedRecords().add(
                        Utils.newRecord(true, table, configuration)
                             .operate(new RecordOperation<R, RuntimeException>() {

                                @Override
                                public R operate(R record) throws RuntimeException {
                                    int index = record.fieldsRow().indexOf(field);

                                    ((AbstractRecord) record).values[index] = id;
                                    ((AbstractRecord) record).originals[index] = id;

                                    return record;
                                }
                            }));
                    }
                }

                // Other values are requested, too. Run another query
                else {
                    returned =
                    create(configuration).select(returning)
                                         .from(table)
                                         .where(field.in(ids))
                                         .fetchInto(table);
                }
            }
        }
    }
}
