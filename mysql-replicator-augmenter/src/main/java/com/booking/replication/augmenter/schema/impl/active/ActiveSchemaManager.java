package com.booking.replication.augmenter.schema.impl.active;

import com.booking.replication.augmenter.model.schema.ColumnSchema;
import com.booking.replication.augmenter.model.schema.SchemaAtPositionCache;
import com.booking.replication.augmenter.model.schema.TableSchema;

import com.booking.replication.augmenter.schema.SchemaManager;
import com.booking.replication.augmenter.schema.impl.SchemaUtil;
import com.booking.replication.supplier.model.TableMapRawEventData;
import com.mysql.jdbc.Driver;

import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

public class ActiveSchemaManager implements SchemaManager {

    public interface Configuration {
        String MYSQL_DRIVER_CLASS   = "augmenter.schema.active.mysql.driver.class";
        String MYSQL_HOSTNAME       = "augmenter.schema.active.mysql.hostname";
        String MYSQL_PORT           = "augmenter.schema.active.mysql.port";
        String MYSQL_SCHEMA         = "augmenter.schema.active.mysql.schema";
        String MYSQL_USERNAME       = "augmenter.schema.active.mysql.username";
        String MYSQL_PASSWORD       = "augmenter.schema.active.mysql.password";

        String ENUM_PATTERN         = "augmenter.context.pattern.enum";
        String SET_PATTERN          = "augmenter.context.pattern.set";

        String BINLOG_MYSQL_HOSTNAME    = "mysql.hostname";
        String BINLOG_MYSQL_PORT        = "mysql.port";
        String BINLOG_MYSQL_SCHEMA      = "mysql.schema";
        String BINLOG_MYSQL_USERNAME    = "mysql.username";
        String BINLOG_MYSQL_PASSWORD    = "mysql.password";
    }

    private static final String DEFAULT_ENUM_PATTERN = "(?<=enum\\()(.*?)(?=\\))";
    private static final String DEFAULT_SET_PATTERN = "(?<=set\\()(.*?)(?=\\))";

    private static final Logger LOG = LogManager.getLogger(ActiveSchemaManager.class);

    private static final String DEFAULT_MYSQL_DRIVER_CLASS = Driver.class.getName();

    private static final String CONNECTION_URL_FORMAT = "jdbc:mysql://%s:%d/%s";
    private static final String BARE_CONNECTION_URL_FORMAT = "jdbc:mysql://%s:%d";

    public static final String SHOW_CREATE_TABLE_SQL = "SHOW CREATE TABLE %s";

    public static final String LIST_COLUMNS_SQL = "SELECT COLUMN_NAME, COLUMN_TYPE, COLLATION_NAME, IS_NULLABLE, "
            + "COLUMN_KEY, COLUMN_DEFAULT,EXTRA, PRIVILEGES, COLUMN_COMMENT, DATA_TYPE, "
            + "CHARACTER_MAXIMUM_LENGTH, CHARACTER_OCTET_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION "
            + " FROM INFORMATION_SCHEMA.COLUMNS "
            + " WHERE TABLE_SCHEMA  = '%s' AND TABLE_NAME = '%s'";

    private final BasicDataSource dataSource;
    private final BasicDataSource binlogDataSource;

    private final Function<String, TableSchema> computeTableSchemaLambda;

    private final SchemaAtPositionCache schemaAtPositionCache;

    private final Map<String, TableMapRawEventData> tableMapEventDataCache = new HashMap<>();

    private final Pattern enumPattern;
    private final Pattern setPattern;

    public ActiveSchemaManager(Map<String, Object> configuration) {

        this.enumPattern = this.getPattern(configuration, ActiveSchemaManager.Configuration.ENUM_PATTERN, ActiveSchemaManager.DEFAULT_ENUM_PATTERN);
        this.setPattern = this.getPattern(configuration, ActiveSchemaManager.Configuration.SET_PATTERN, ActiveSchemaManager.DEFAULT_SET_PATTERN);

        this.dataSource = initDatasource(configuration);
        this.binlogDataSource = initBinlogDatasource(configuration);
        this.schemaAtPositionCache = new SchemaAtPositionCache();

        String schema = getMysqlSchema(configuration);

        this.computeTableSchemaLambda = (tableName) -> {
            try {
                TableSchema ts = SchemaUtil.computeTableSchemaFromActiveSchemaInstance(
                    schema,
                    tableName,
                    ActiveSchemaManager.this.dataSource,
                    ActiveSchemaManager.this.binlogDataSource,
                    this.enumPattern,
                    this.setPattern
                );
                return ts;
            } catch (Exception e) {
                ActiveSchemaManager.LOG.warn(
                        String.format("error listing columns from table \"%s\" : %s", tableName, e.getMessage()),
                        e
                );
                return null;
            }
        };
    }

    private Pattern getPattern(Map<String, Object> configuration, String configurationPath, String configurationDefault) {
        Object pattern = configuration.getOrDefault(
                configurationPath,
                configurationDefault
        );

        if ( pattern != null ) {
            return Pattern.compile(pattern.toString(),Pattern.CASE_INSENSITIVE);
        }

        return null;
    }

    private String getMysqlSchema(Map<String, Object> configuration) {
        Object schema       = configuration.get(Configuration.MYSQL_SCHEMA);
        Objects.requireNonNull(schema, String.format("Configuration required: %s", Configuration.MYSQL_SCHEMA));

        return schema.toString();
    }

    public BasicDataSource initDatasource(Map<String, Object> configuration) {
        Object driverClass  = configuration.getOrDefault(Configuration.MYSQL_DRIVER_CLASS, ActiveSchemaManager.DEFAULT_MYSQL_DRIVER_CLASS);
        Object hostname     = configuration.get(Configuration.MYSQL_HOSTNAME);
        Object port         = configuration.getOrDefault(Configuration.MYSQL_PORT, "3306");
        Object schema       = configuration.get(Configuration.MYSQL_SCHEMA);
        Object username     = configuration.get(Configuration.MYSQL_USERNAME);
        Object password     = configuration.get(Configuration.MYSQL_PASSWORD);

        Objects.requireNonNull(hostname, String.format("Configuration required: %s", Configuration.MYSQL_HOSTNAME));
        Objects.requireNonNull(schema, String.format("Configuration required: %s", Configuration.MYSQL_SCHEMA));
        Objects.requireNonNull(username, String.format("Configuration required: %s", Configuration.MYSQL_USERNAME));
        Objects.requireNonNull(password, String.format("Configuration required: %s", Configuration.MYSQL_PASSWORD));

        return this.getDataSource(driverClass.toString(), hostname.toString(), Integer.parseInt(port.toString()), schema.toString(), username.toString(), password.toString());
    }

    public BasicDataSource initBinlogDatasource(Map<String, Object> configuration) {
        Object driverClass = configuration.getOrDefault(Configuration.MYSQL_DRIVER_CLASS, ActiveSchemaManager.DEFAULT_MYSQL_DRIVER_CLASS);

        Object hostname = configuration.get(Configuration.BINLOG_MYSQL_HOSTNAME);
        Object port     = configuration.getOrDefault(Configuration.BINLOG_MYSQL_PORT, "3306");
        Object schema   = configuration.get(Configuration.BINLOG_MYSQL_SCHEMA);
        Object username = configuration.get(Configuration.BINLOG_MYSQL_USERNAME);
        Object password = configuration.get(Configuration.BINLOG_MYSQL_PASSWORD);

        Objects.requireNonNull(hostname, String.format("Configuration required: %s", Configuration.BINLOG_MYSQL_HOSTNAME));
        Objects.requireNonNull(schema, String.format("Configuration required: %s", Configuration.BINLOG_MYSQL_SCHEMA));
        Objects.requireNonNull(username, String.format("Configuration required: %s", Configuration.BINLOG_MYSQL_USERNAME));
        Objects.requireNonNull(password, String.format("Configuration required: %s", Configuration.BINLOG_MYSQL_PASSWORD));

        return this.getDataSource(driverClass.toString(), this.getFirst(hostname), Integer.parseInt(port.toString()), schema.toString(), username.toString(), password.toString());
    }

    private BasicDataSource getDataSource(String driverClass, String hostname, int port, String schema, String username, String password) {
        BasicDataSource dataSource = new BasicDataSource();

        dataSource.setDriverClassName(driverClass);
        dataSource.setUrl(String.format(ActiveSchemaManager.CONNECTION_URL_FORMAT, hostname, port, schema));
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setTestOnBorrow(true);
        return dataSource;
    }

    private BasicDataSource getDataSource(String driverClass, String hostname, int port, String username, String password) {
        BasicDataSource dataSource = new BasicDataSource();

        dataSource.setDriverClassName(driverClass);
        dataSource.setUrl(String.format(ActiveSchemaManager.BARE_CONNECTION_URL_FORMAT, hostname, port));
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setTestOnBorrow(true);
        return dataSource;
    }

    public boolean createDbIfNotExists(Map<String, Object> configuration) {
        Object driverClass  = configuration.getOrDefault(Configuration.MYSQL_DRIVER_CLASS, ActiveSchemaManager.DEFAULT_MYSQL_DRIVER_CLASS);
        Object hostname     = configuration.get(Configuration.MYSQL_HOSTNAME);
        Object port         = configuration.getOrDefault(Configuration.MYSQL_PORT, "3306");
        Object schema1      = configuration.get(Configuration.MYSQL_SCHEMA);
        Object username     = configuration.get(Configuration.MYSQL_USERNAME);
        Object password     = configuration.get(Configuration.MYSQL_PASSWORD);

        Objects.requireNonNull(hostname, String.format("Configuration required: %s", Configuration.MYSQL_HOSTNAME));
        Objects.requireNonNull(schema1, String.format("Configuration required: %s", Configuration.MYSQL_SCHEMA));
        Objects.requireNonNull(username, String.format("Configuration required: %s", Configuration.MYSQL_USERNAME));
        Objects.requireNonNull(password, String.format("Configuration required: %s", Configuration.MYSQL_PASSWORD));

        String schema = schema1.toString();
        BasicDataSource dataSource = this.getDataSource(driverClass.toString(), this.getFirst(hostname), Integer.parseInt(port.toString()), username.toString(), password.toString());
        try (Connection conn = dataSource.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("SHOW DATABASES LIKE ?");
            stmt.setString(1, schema);
            ResultSet resultSet = stmt.executeQuery();
            if (resultSet.next()) {
                LOG.info("Database " + schema + " already exists in active schema.");
                return true;
            }

            LOG.info("Database " + schema + " doesn't exists in active schema. Creating ...");
            PreparedStatement createDb = conn.prepareStatement("CREATE DATABASE " + schema);
            return createDb.execute();
        } catch (SQLException e) {
            LOG.error("Could not establist connection to: " + hostname, e);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private String getFirst(Object object) {
        if (List.class.isInstance(object)) {
            return ((List<String>) object).get(0);
        } else {
            return object.toString();
        }
    }

    @Override
    public boolean execute(String tableName, String query) {

        try (Connection connection = this.dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            if (tableName != null) {
                this.schemaAtPositionCache.removeTableFromCache(tableName);
            }

            boolean executed = statement.execute(query);

            if (tableName != null) {
                this.schemaAtPositionCache.reloadTableSchema(
                        tableName,
                        this.computeTableSchemaLambda
                );
            }
            return executed;
        } catch (SQLException exception) {
            ActiveSchemaManager.LOG.warn(String.format("error executing query \"%s\": %s", query, exception.getMessage()));
            return false;
        }
    }

    @Override
    public void updateTableMapCache(TableMapRawEventData tableMapRawEventData) {
        this.tableMapEventDataCache.put(tableMapRawEventData.getTable(), tableMapRawEventData);
    }

    @Override
    public List<ColumnSchema> listColumns(String tableName) {
        TableSchema tableSchema =
                this.schemaAtPositionCache.getTableColumns(tableName, this.computeTableSchemaLambda);
        if (tableSchema == null) {
            return null;
        }

        return (List<ColumnSchema>) tableSchema.getColumnSchemas();
    }

    @Override
    public boolean dropTable(String tableName) throws SQLException {
        try (Connection conn = this.dataSource.getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("DROP TABLE IF EXISTS " + tableName);
            return stmt.execute();
        }
    }

    @Override
    public String getCreateTable(String tableName) {
        try (Connection connection = this.dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(String.format(ActiveSchemaManager.SHOW_CREATE_TABLE_SQL, tableName))) {
            if (resultSet.next()) {
                return resultSet.getString(2);
            } else {
                return null;
            }
        } catch (SQLException exception) {
            ActiveSchemaManager.LOG.warn(String.format("error getting create table from table \"%s\"", tableName, exception.getMessage()));
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            this.dataSource.close();
            this.binlogDataSource.close();
        } catch (SQLException exception) {
            throw new IOException("error closing active schema loader", exception);
        }
    }

    @Override
    public Function<String, TableSchema> getComputeTableSchemaLambda() {
        return this.computeTableSchemaLambda;
    }

}