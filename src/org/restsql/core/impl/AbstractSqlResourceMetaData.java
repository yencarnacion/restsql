/* Copyright (c) restSQL Project Contributors. Licensed under MIT. */
package org.restsql.core.impl;

import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlType;

import org.restsql.core.ColumnMetaData;
import org.restsql.core.Config;
import org.restsql.core.Factory;
import org.restsql.core.SqlResourceException;
import org.restsql.core.SqlResourceMetaData;
import org.restsql.core.TableMetaData;
import org.restsql.core.TableMetaData.TableRole;
import org.restsql.core.sqlresource.SqlResourceDefinition;
import org.restsql.core.sqlresource.SqlResourceDefinitionUtils;
import org.restsql.core.sqlresource.Table;

/**
 * Represents meta data for sql resource. Queries database for table and column meta data and primary and foreign keys.
 * 
 * @todo Read-only columns do not work with PostgreSQL
 * @author Mark Sawers
 */
@XmlRootElement(name = "sqlResourceMetaData", namespace = "http://restsql.org/schema")
@XmlType(name = "SqlResourceMetaData", namespace = "http://restsql.org/schema", propOrder = { "resName",
		"hierarchical", "multipleDatabases", "tables", "parentTableName", "childTableName", "joinTableName",
		"parentPlusExtTableNames", "childPlusExtTableNames", "joinTableNames", "allReadColumnNames",
		"parentReadColumnNames", "childReadColumnNames" })
public abstract class AbstractSqlResourceMetaData implements SqlResourceMetaData {
	private static final int DEFAULT_NUMBER_DATABASES = 5;
	private static final int DEFAULT_NUMBER_TABLES = 10;

	@SuppressWarnings("unused")
	@XmlElementWrapper(name = "allReadColumns", required = true)
	@XmlElement(name = "column", required = true)
	private List<String> allReadColumnNames;

	@XmlTransient
	private List<ColumnMetaData> allReadColumns;

	@SuppressWarnings("unused")
	@XmlElementWrapper(name = "childPlusExtTables", required = true)
	@XmlElement(name = "table")
	private List<String> childPlusExtTableNames;

	@XmlTransient
	private List<TableMetaData> childPlusExtTables;

	@SuppressWarnings("unused")
	@XmlElementWrapper(name = "childReadColumns", required = true)
	@XmlElement(name = "column")
	private List<String> childReadColumnNames;

	@XmlTransient
	private List<ColumnMetaData> childReadColumns;

	@XmlTransient
	private TableMetaData childTable;

	@SuppressWarnings("unused")
	@XmlElement(name = "childTable")
	private String childTableName;

	@XmlTransient
	private SqlResourceDefinition definition;

	@XmlTransient
	private boolean extendedMetadataIsBuilt;

	@XmlAttribute
	private boolean hierarchical;

	@XmlTransient
	private List<TableMetaData> joinList;

	@XmlTransient
	private TableMetaData joinTable;

	@SuppressWarnings("unused")
	@XmlElement(name = "joinTable")
	private String joinTableName;

	@SuppressWarnings("unused")
	@XmlElementWrapper(name = "joinTables")
	@XmlElement(name = "table")
	private List<String> joinTableNames;

	@XmlAttribute
	private boolean multipleDatabases;

	@SuppressWarnings("unused")
	@XmlElementWrapper(name = "parentPlusExtTables", required = true)
	@XmlElement(name = "table", required = true)
	private List<String> parentPlusExtTableNames;

	@XmlTransient
	private List<TableMetaData> parentPlusExtTables;

	@SuppressWarnings("unused")
	@XmlElementWrapper(name = "parentReadColumns", required = true)
	@XmlElement(name = "column", required = true)
	private List<String> parentReadColumnNames;

	@XmlTransient
	private List<ColumnMetaData> parentReadColumns;

	@XmlTransient
	private TableMetaData parentTable;

	@SuppressWarnings("unused")
	@XmlElement(name = "parentTable", required = true)
	private String parentTableName;

	@XmlAttribute(required = true)
	private String resName;

	/** Map<database.table, TableMetaData> */
	@XmlTransient
	private Map<String, TableMetaData> tableMap;

	@XmlElementWrapper(name = "tables", required = true)
	@XmlElement(name = "table", type = TableMetaDataImpl.class, required = true)
	private List<TableMetaData> tables;

	// Public methods to retrieve metadata

	@Override
	public List<ColumnMetaData> getAllReadColumns() {
		return allReadColumns;
	}

	@Override
	public TableMetaData getChild() {
		return childTable;
	}

	@Override
	public List<TableMetaData> getChildPlusExtTables() {
		return childPlusExtTables;
	}

	@Override
	public List<ColumnMetaData> getChildReadColumns() {
		return childReadColumns;
	}

	@Override
	public TableMetaData getJoin() {
		return joinTable;
	}

	@Override
	public List<TableMetaData> getJoinList() {
		return joinList;
	}

	@Override
	public int getNumberTables() {
		return tables.size();
	}

	@Override
	public TableMetaData getParent() {
		return parentTable;
	}

	@Override
	public List<TableMetaData> getParentPlusExtTables() {
		return parentPlusExtTables;
	}

	@Override
	public List<ColumnMetaData> getParentReadColumns() {
		return parentReadColumns;
	}

	@Override
	public Map<String, TableMetaData> getTableMap() {
		return tableMap;
	}

	@Override
	public List<TableMetaData> getTables() {
		return tables;
	}

	@Override
	public boolean hasJoinTable() {
		return joinTable != null;
	}

	@Override
	public boolean hasMultipleDatabases() {
		return multipleDatabases;
	}

	@Override
	public boolean isHierarchical() {
		return hierarchical;
	}

	/** Populates metadata using definition. */
	@Override
	public void setDefinition(final String resName, final SqlResourceDefinition definition)
			throws SqlResourceException {
		this.resName = resName;
		this.definition = definition;
		Connection connection = null;
		String sql = null;
		SqlResourceDefinitionUtils.validate(definition);
		try {
			connection = Factory.getConnection(SqlResourceDefinitionUtils.getDefaultDatabase(definition));
			final Statement statement = connection.createStatement();
			sql = getSqlMainQuery(definition);
			if (Config.logger.isDebugEnabled()) {
				Config.logger.debug("Loading meta data for " + this.resName + " - " + sql);
			}
			final ResultSet resultSet = statement.executeQuery(sql);
			resultSet.next();
			buildTablesAndColumns(resultSet, connection);
			resultSet.close();
			statement.close();
			buildPrimaryKeys(connection);
			buildInvisibleForeignKeys(connection);
			buildJoinTableMetadata(connection);
		} catch (final SQLException exception) {
			throw new SqlResourceException(exception, sql);
		} finally {
			if (connection != null) {
				try {
					connection.close();
				} catch (final SQLException ignored) {
				}
			}
		}
		hierarchical = getChild() != null;
	}

	/** Returns XML representation. */
	@Override
	public String toXml() {
		// Build extended metadata for serialization if first time through
		if (!extendedMetadataIsBuilt) {
			parentTableName = getQualifiedTableName(parentTable);
			childTableName = getQualifiedTableName(childTable);
			joinTableName = getQualifiedTableName(joinTable);
			parentPlusExtTableNames = getQualifiedTableNames(parentPlusExtTables);
			childPlusExtTableNames = getQualifiedTableNames(childPlusExtTables);
			allReadColumnNames = getQualifiedColumnNames(allReadColumns);
			childReadColumnNames = getQualifiedColumnNames(childReadColumns);
			parentReadColumnNames = getQualifiedColumnNames(parentReadColumns);
			extendedMetadataIsBuilt = true;
		}

		try {
			final JAXBContext context = JAXBContext.newInstance(AbstractSqlResourceMetaData.class);
			final Marshaller marshaller = context.createMarshaller();
			marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
			final StringWriter writer = new StringWriter();
			marshaller.marshal(this, writer);
			return writer.toString();
		} catch (final JAXBException exception) {
			return exception.toString();
		}
	}

	// Protected methods for database-specific implementation

	/**
	 * Retrieves database name from result set meta data. Hook method for buildTablesAndColumns() allows
	 * database-specific overrides.
	 */
	protected String getColumnDatabaseName(final SqlResourceDefinition definition,
			final ResultSetMetaData resultSetMetaData, final int colNumber) throws SQLException {
		return resultSetMetaData.getCatalogName(colNumber);
	}

	/**
	 * Retrieves actual column name from result set meta data. Hook method for buildTablesAndColumns() allows
	 * database-specific overrides.
	 */
	protected String getColumnName(final SqlResourceDefinition definition,
			final ResultSetMetaData resultSetMetaData, final int colNumber) throws SQLException {
		return resultSetMetaData.getColumnName(colNumber);
	}

	/**
	 * Retrieves table name from result set meta data. Hook method for buildTablesAndColumns() allows database-specific
	 * overrides.
	 */
	protected String getColumnTableName(final SqlResourceDefinition definition,
			final ResultSetMetaData resultSetMetaData, final int colNumber) throws SQLException {
		return resultSetMetaData.getTableName(colNumber);
	}

	/** Retrieves database-specific table name used in SQL statements. Used to build join table meta data. */
	protected abstract String getQualifiedTableName(Connection connection, String databaseName,
			String tableName) throws SQLException;

	/** Retrieves database-specific table name used in SQL statements. */
	protected abstract String getQualifiedTableName(final SqlResourceDefinition definition,
			final ResultSetMetaData resultSetMetaData, final int colNumber) throws SQLException;

	/**
	 * Retrieves sql for querying columns. Hook method for buildInvisibleForeignKeys() and buildJoinTableMetadata()
	 * allows database-specific overrides.
	 */
	protected abstract String getSqlColumnsQuery();

	/**
	 * Retrieves sql for the main query based on the definition. Optimized to retrieve only one row. Hook method for
	 * constructor allows database-specific overrides.
	 */
	protected String getSqlMainQuery(final SqlResourceDefinition definition) {
		return definition.getQuery().getValue() + " LIMIT 1 OFFSET 0";
	}

	/**
	 * Retrieves sql for querying primary keys. Hook method for buildPrimaryKeys allows database-specific overrides.
	 */
	protected abstract String getSqlPkQuery();

	private void buildInvisibleForeignKeys(final Connection connection) throws SQLException {
		final PreparedStatement statement = connection.prepareStatement(getSqlColumnsQuery());
		ResultSet resultSet = null;
		try {
			for (final TableMetaData table : tables) {
				if (!table.isParent()) {
					statement.setString(1, table.getDatabaseName());
					statement.setString(2, table.getTableName());
					resultSet = statement.executeQuery();
					while (resultSet.next()) {
						final String columnName = resultSet.getString(1);
						if (!table.getColumns().containsKey(columnName)) {
							TableMetaData mainTable;
							switch (table.getTableRole()) {
								case ChildExtension:
									mainTable = childTable;
									break;
								default: // Child, ParentExtension, Unknown
									mainTable = parentTable;
							}
							// Look for a pk on the main table with the same name
							for (final ColumnMetaData pk : mainTable.getPrimaryKeys()) {
								if (columnName.equals(pk.getColumnName())) {
									final ColumnMetaDataImpl fkColumn = new ColumnMetaDataImpl(
											table.getDatabaseName(), table.getQualifiedTableName(),
											table.getTableName(), table.getTableRole(), columnName,
											pk.getColumnLabel(), resultSet.getString(2), this);
									((TableMetaDataImpl) table).addColumn(fkColumn);
								}
							}
						}
					}
				}
			}
		} catch (final SQLException exception) {
			if (resultSet != null) {
				resultSet.close();
			}
			if (statement != null) {
				statement.close();
			}
			throw exception;
		}
	}

	private void buildJoinTableMetadata(final Connection connection) throws SQLException {
		// Join table could have been idenitfied in buildTablesAndColumns(), but not always
		final Table joinDef = SqlResourceDefinitionUtils.getTable(definition, TableRole.Join);
		if (joinDef != null && joinTable == null) {
			// Determine table and database name
			String tableName, databaseName;
			final String possiblyQualifiedTableName = joinDef.getName();
			final int dotIndex = possiblyQualifiedTableName.indexOf('.');
			if (dotIndex > 0) {
				tableName = possiblyQualifiedTableName.substring(0, dotIndex);
				databaseName = possiblyQualifiedTableName.substring(dotIndex + 1);
			} else {
				tableName = possiblyQualifiedTableName;
				databaseName = SqlResourceDefinitionUtils.getDefaultDatabase(definition);
			}

			final String qualifiedTableName = getQualifiedTableName(connection, databaseName, tableName);

			// Create table and add to special lists
			joinTable = new TableMetaDataImpl(tableName, qualifiedTableName, databaseName, TableRole.Join);
			tableMap.put(joinTable.getQualifiedTableName(), joinTable);
			tables.add(joinTable);
			joinList = new ArrayList<TableMetaData>(1);
			joinList.add(joinTable);

			// Execute metadata query and populate metadata structure
			PreparedStatement statement = null;
			ResultSet resultSet = null;
			try {
				statement = connection.prepareStatement(getSqlColumnsQuery());
				statement.setString(1, databaseName);
				statement.setString(2, tableName);
				resultSet = statement.executeQuery();
				while (resultSet.next()) {
					final String columnName = resultSet.getString(1);
					final ColumnMetaDataImpl column = new ColumnMetaDataImpl(databaseName,
							qualifiedTableName, tableName, TableRole.Join, columnName, columnName,
							resultSet.getString(2), this);
					((TableMetaDataImpl) joinTable).addColumn(column);
				}
			} catch (final SQLException exception) {
				if (resultSet != null) {
					resultSet.close();
				}
				if (statement != null) {
					statement.close();
				}
				throw exception;
			}
		}
	}

	/**
	 * Builds list of primary key column labels.
	 * 
	 * @param Connection connection
	 * @throws SqlResourceException if a database access error occurs
	 */
	private void buildPrimaryKeys(final Connection connection) throws SQLException {
		final PreparedStatement statement = connection.prepareStatement(getSqlPkQuery());
		ResultSet resultSet = null;
		try {
			for (final TableMetaData table : tables) {
				statement.setString(1, table.getDatabaseName());
				statement.setString(2, table.getTableName());
				resultSet = statement.executeQuery();
				while (resultSet.next()) {
					final String columnName = resultSet.getString(1);
					for (final ColumnMetaData column : table.getColumns().values()) {
						if (columnName.equals(column.getColumnName())) {
							((ColumnMetaDataImpl) column).setPrimaryKey(true);
							((TableMetaDataImpl) table).addPrimaryKey(column);
						}
					}
				}
			}
		} catch (final SQLException exception) {
			if (resultSet != null) {
				resultSet.close();
			}
			if (statement != null) {
				statement.close();
			}
			throw exception;
		}
	}

	// Private utils

	/**
	 * Builds table and column meta data.
	 * 
	 * @param resultSet resultSet
	 * @param connection database connection - used to get qualified name for read-only columns
	 * @throws SQLException if a database access error occurs
	 * @throws SqlResourceException if definition is invalid
	 */
	@SuppressWarnings("fallthrough")
	private void buildTablesAndColumns(final ResultSet resultSet, final Connection connection)
			throws SQLException, SqlResourceException {
		final ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
		final int columnCount = resultSetMetaData.getColumnCount();

		allReadColumns = new ArrayList<ColumnMetaData>(columnCount);
		parentReadColumns = new ArrayList<ColumnMetaData>(columnCount);
		childReadColumns = new ArrayList<ColumnMetaData>(columnCount);
		tableMap = new HashMap<String, TableMetaData>(DEFAULT_NUMBER_TABLES);
		tables = new ArrayList<TableMetaData>(DEFAULT_NUMBER_TABLES);
		childPlusExtTables = new ArrayList<TableMetaData>(DEFAULT_NUMBER_TABLES);
		parentPlusExtTables = new ArrayList<TableMetaData>(DEFAULT_NUMBER_TABLES);
		final HashSet<String> databases = new HashSet<String>(DEFAULT_NUMBER_DATABASES);

		for (int colNumber = 1; colNumber <= columnCount; colNumber++) {
			final String databaseName, qualifiedTableName, tableName;
			if (resultSetMetaData.isReadOnly(colNumber)) {
				databaseName = SqlResourceDefinitionUtils.getDefaultDatabase(definition);
				tableName = SqlResourceDefinitionUtils.getTable(definition, TableRole.Parent).getName();
				qualifiedTableName = getQualifiedTableName(connection, databaseName, tableName);
			} else {
				databaseName = getColumnDatabaseName(definition, resultSetMetaData, colNumber);
				databases.add(databaseName);
				tableName = getColumnTableName(definition, resultSetMetaData, colNumber);
				qualifiedTableName = getQualifiedTableName(definition, resultSetMetaData, colNumber);
			}

			final ColumnMetaDataImpl column = new ColumnMetaDataImpl(colNumber, databaseName,
					qualifiedTableName, tableName, getColumnName(definition, resultSetMetaData, colNumber),
					resultSetMetaData.getColumnLabel(colNumber),
					resultSetMetaData.getColumnTypeName(colNumber),
					resultSetMetaData.getColumnType(colNumber), resultSetMetaData.isReadOnly(colNumber), this);

			TableMetaDataImpl table = (TableMetaDataImpl) tableMap.get(column.getQualifiedTableName());
			if (table == null) {
				// Create table metadata object and add to special references
				final Table tableDef = SqlResourceDefinitionUtils.getTable(definition, column);
				if (tableDef == null) {
					throw new SqlResourceException("Definition requires table element for "
							+ column.getTableName() + ", referenced by column " + column.getColumnLabel());
				}
				table = new TableMetaDataImpl(tableName, qualifiedTableName, databaseName,
						TableRole.valueOf(tableDef.getRole()));
				tableMap.put(column.getQualifiedTableName(), table);
				tables.add(table);

				switch (table.getTableRole()) {
					case Parent:
						parentTable = table;
						if (tableDef.getAlias() != null) {
							table.setTableAlias(tableDef.getAlias());
						}
						// fall through
					case ParentExtension:
						parentPlusExtTables.add(table);
						break;
					case Child:
						childTable = table;
						if (tableDef.getAlias() != null) {
							table.setTableAlias(tableDef.getAlias());
						}
						// fall through
					case ChildExtension:
						childPlusExtTables.add(table);
						break;
					case Join: // unlikely to be in the select columns, but just in case
						joinTable = table;
						joinList = new ArrayList<TableMetaData>(1);
						joinList.add(joinTable);
						break;
					default: // Unknown
				}
			}

			// Add column to the table
			table.addColumn(column);
			column.setTableRole(table.getTableRole());

			// Add column to special column lists
			allReadColumns.add(column);
			switch (table.getTableRole()) {
				case Parent:
				case ParentExtension:
					parentReadColumns.add(column);
					break;
				case Child:
				case ChildExtension:
					childReadColumns.add(column);
					break;
			}
		}

		// Determine number of databases
		multipleDatabases = databases.size() > 1;
	}

	private List<String> getQualifiedColumnNames(final List<ColumnMetaData> columns) {
		if (columns != null) {
			final List<String> names = new ArrayList<String>(columns.size());
			for (final ColumnMetaData column : columns) {
				names.add(column.getQualifiedColumnName());
			}
			return names;
		} else {
			return null;
		}
	}

	private String getQualifiedTableName(final TableMetaData table) {
		if (table != null) {
			return table.getQualifiedTableName();
		} else {
			return null;
		}
	}

	private List<String> getQualifiedTableNames(final List<TableMetaData> tables) {
		if (tables != null) {
			final List<String> names = new ArrayList<String>(tables.size());
			for (final TableMetaData table : tables) {
				names.add(table.getQualifiedTableName());
			}
			return names;
		} else {
			return null;
		}
	}
}