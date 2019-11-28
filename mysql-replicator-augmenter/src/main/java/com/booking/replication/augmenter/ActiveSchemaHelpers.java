package com.booking.replication.augmenter;

import com.booking.replication.augmenter.model.schema.ColumnSchema;
import com.booking.replication.augmenter.model.schema.DataType;
import com.booking.replication.augmenter.model.schema.FullTableName;
import com.booking.replication.augmenter.model.schema.TableSchema;

import org.apache.commons.dbcp2.BasicDataSource;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

public class ActiveSchemaHelpers {

    public static TableSchema computeTableSchema(
            String schemaName,
            String tableName,
            BasicDataSource activeSchemaDataSource) {

        try (Connection activeSchemaConnection = activeSchemaDataSource.getConnection()) {

            Statement statementActiveSchemaListColumns      = activeSchemaConnection.createStatement();
            Statement statementActiveSchemaShowCreateTable  = activeSchemaConnection.createStatement();

            List<ColumnSchema> columnList = new ArrayList<>();

            ResultSet resultSet;

            resultSet = statementActiveSchemaListColumns.executeQuery(
                 String.format(
                         ActiveSchemaManager.LIST_COLUMNS_SQL,
                         schemaName,
                         tableName
                 )
            );

            while (resultSet.next()) {

                boolean isNullable = (resultSet.getString("IS_NULLABLE").equals("NO") ? false : true);

                DataType dataType = DataType.byCode(resultSet.getString("DATA_TYPE"));

                ColumnSchema columnSchema = new ColumnSchema(
                        resultSet.getString("COLUMN_NAME"),
                        dataType,
                        resultSet.getString("COLUMN_TYPE"),
                        isNullable,
                        resultSet.getString("COLUMN_KEY"),
                        resultSet.getString("EXTRA")
                );

                columnSchema
                        .setCollation(resultSet.getString("COLLATION_NAME"))
                        .setDefaultValue(resultSet.getString("COLUMN_DEFAULT"))
                        .setDateTimePrecision(resultSet.getInt("DATETIME_PRECISION"))
                        .setCharMaxLength(resultSet.getInt("CHARACTER_MAXIMUM_LENGTH"))
                        .setCharOctetLength(resultSet.getInt("CHARACTER_OCTET_LENGTH"))
                        .setNumericPrecision(resultSet.getInt("NUMERIC_PRECISION"))
                        .setNumericScale(resultSet.getInt("NUMERIC_SCALE"));

                columnList.add(columnSchema);
            }

            DatabaseMetaData dbm = activeSchemaConnection.getMetaData();
            boolean tableExists = false;
            ResultSet tables = dbm.getTables(schemaName, null, tableName, null);
            if (tables.next()) {
                tableExists = true; // DLL statement was not table DROP
            }

            String tableCreateStatement = "";
            if (tableExists) {
                ResultSet showCreateTableResultSet = statementActiveSchemaShowCreateTable.executeQuery(
                        String.format(ActiveSchemaManager.SHOW_CREATE_TABLE_SQL, tableName)
                );
                ResultSetMetaData showCreateTableResultSetMetadata = showCreateTableResultSet.getMetaData();
                tableCreateStatement = ActiveSchemaHelpers.getCreateTableStatement(tableName, showCreateTableResultSet, showCreateTableResultSetMetadata);
            }

            return new TableSchema(
                    new FullTableName(schemaName, tableName),
                    columnList,
                    tableCreateStatement
            );

        } catch (SQLException exception) {
            throw new IllegalStateException("Could not get table schema: ", exception);
        }
    }


    public static String getCreateTableStatement(String tableName, ResultSet showCreateTableResultSet, ResultSetMetaData showCreateTableResultSetMetadata) throws SQLException {
        String tableCreateStatement = null;
        while (showCreateTableResultSet.next()) {
            if (showCreateTableResultSetMetadata.getColumnCount() != 2) {
                throw new SQLException("SHOW CREATE TABLE should return 2 columns.");
            }
            String returnedTableName = showCreateTableResultSet.getString(1);
            if (!returnedTableName.equalsIgnoreCase(tableName)) {
                throw new SQLException("We asked for '" + tableName + "' and got '" + returnedTableName + "'");
            }
            tableCreateStatement = showCreateTableResultSet.getString(2);
        }
        return tableCreateStatement;
    }

    /**
     * Mangle name of the active schema before applying DDL statements.
     *
     * @param query             Query string
     * @param replicantDbName   Database name
     * @return                  Rewritten query
     */
    public static String rewriteActiveSchemaName(String query, String replicantDbName) {
        String dbNamePattern =
                "( " + replicantDbName + "\\.)" +
                        "|" +
                        "( `" + replicantDbName + "`\\.)";
        query = query.replaceAll(dbNamePattern, " ");

        return query;
    }

}
