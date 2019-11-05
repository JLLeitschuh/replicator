package com.booking.replication.augmenter;

import com.booking.replication.augmenter.model.schema.ColumnSchema;
import com.booking.replication.augmenter.model.schema.TableSchema;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;

import java.io.Closeable;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;

public interface SchemaManager extends Closeable {

    boolean execute(String tableName, String query);

    void updateTableMapCache(TableMapEventData tableMapEventData);

    List<ColumnSchema> listColumns(String tableName);

    boolean dropTable(String tableName) throws SQLException;

    String getCreateTable(String tableName);

    @Override
    void close() throws IOException;

    Function<String, TableSchema> getComputeTableSchemaLambda();

}
