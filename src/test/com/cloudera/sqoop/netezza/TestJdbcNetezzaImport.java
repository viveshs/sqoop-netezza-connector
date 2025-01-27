// (c) Copyright 2010 Cloudera, Inc. All Rights Reserved.

package com.cloudera.sqoop.netezza;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.sqoop.Sqoop;
import org.apache.sqoop.SqoopOptions;
import org.apache.sqoop.manager.ConnManager;
import org.apache.sqoop.tool.ImportTool;
import org.apache.sqoop.tool.SqoopTool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Test the Netezza EDW connector for jdbc mode imports.
 */
public class TestJdbcNetezzaImport {

  protected Configuration conf;
  protected SqoopOptions options;
  protected ConnManager manager;
  protected Connection conn;

  @Before
  public void setUp() throws IOException, InterruptedException, SQLException {
//    new NzTestUtil().clearNzSessions();
    System.setProperty(Sqoop.SQOOP_RETHROW_PROPERTY, "true");
    conf = NzTestUtil.initConf(new Configuration());
    options = getSqoopOptions(conf);
    manager = NzTestUtil.getNzManager(options);
    conn = manager.getConnection();
  }

  @After
  public void tearDown() throws SQLException {
    if (null != conn) {
      this.conn.close();
    }
  }

  protected String getDbFriendlyName() {
    return "nz";
  }

  /** Base directory for all temporary data. */
  public static final String TEMP_BASE_DIR;

  /** Where to import table data to in the local filesystem for testing. */
  public static final String LOCAL_WAREHOUSE_DIR;

  // Initializer for the above.
  static {
    String tmpDir = System.getProperty("test.build.data", "/tmp/");
    if (!tmpDir.endsWith(File.separator)) {
      tmpDir = tmpDir + File.separator;
    }

    TEMP_BASE_DIR = tmpDir;
    LOCAL_WAREHOUSE_DIR = TEMP_BASE_DIR + "sqoop/warehouse";
  }

  /**
   * Create a SqoopOptions to connect to the manager.
   */
  public SqoopOptions getSqoopOptions(Configuration config) {
    SqoopOptions sqoopOptions = new SqoopOptions(config);
    NzTestUtil.initSqoopOptions(sqoopOptions);

    // Make sure we set numMappers > 1 to use DATASLICEID partitioning.
    sqoopOptions.setNumMappers(2);

    return sqoopOptions;
  }

  protected String assembleTableOrViewName(String schema, String tableOrView) {

    if (schema != null) {
      return schema + "." + tableOrView;
    } else{
      return tableOrView;
    }
  }

  protected void createSchema(Connection c, String schema) throws SQLException {
    NzTestUtil.dropSchemaIfExists(c, schema);

    String createSchemaStmt = "CREATE SCHEMA " + schema;

    PreparedStatement stmt = null;
    try {
      stmt = c.prepareStatement(createSchemaStmt);
      stmt.executeUpdate();
      c.commit();
    } finally {
      if (null != stmt)  {
        stmt.close();
      }
    }
  }

  protected void createTable(Connection c, String schema, String tableName, String... colTypes)
      throws SQLException {

    if (null == colTypes || colTypes.length == 0) {
      throw new SQLException("must have at least one column");
    }

    NzTestUtil.dropTableIfExists(c, tableName);
    StringBuilder sb = new StringBuilder();
    sb.append("CREATE TABLE ");
    if (schema != null) {
      createSchema(c, schema);
    }
    sb.append(assembleTableOrViewName(schema, tableName));
    sb.append(" (");
    boolean first = true;
    for (int i = 0; i < colTypes.length; i++) {
      if (!first) {
        sb.append(", ");
      }
      first = false;

      sb.append("col" + i);
      sb.append(" ");
      sb.append(colTypes[i]);
    }
    sb.append(" )");

    PreparedStatement stmt = null;
    try {
      stmt = c.prepareStatement(sb.toString());
      stmt.executeUpdate();
      c.commit();
    } finally {
      if (null != stmt)  {
        stmt.close();
      }
    }
  }

  protected void createView(Connection c, String schema, String viewName, String selectQuery)
      throws SQLException {

    if (selectQuery == null) {
      throw new SQLException("must have a select query");
    }

    NzTestUtil.dropViewIfExists(c, viewName);

    if (schema != null) {
      createSchema(c, schema);
    }

    String createTableStmt = "CREATE VIEW " + assembleTableOrViewName(schema, viewName) + " AS " + selectQuery;

    PreparedStatement stmt = null;
    try {
      stmt = c.prepareStatement(createTableStmt);
      stmt.executeUpdate();
      c.commit();
    } finally {
      if (stmt != null) {
        stmt.close();
      }
    }
  }

  protected void addRow(Connection c, String schema, String tableName, String... values)
      throws SQLException {

    StringBuilder sb = new StringBuilder();
    sb.append("INSERT INTO ");
    if (schema != null) {
      sb.append(schema);
      sb.append(".");
    }
    sb.append(tableName);

    sb.append(" VALUES (");
    boolean first = true;
    for (String val : values) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append(val);
    }
    sb.append(")");

    PreparedStatement stmt = null;
    try {
      stmt = c.prepareStatement(sb.toString());
      stmt.executeUpdate();
      c.commit();
    } finally {
      if (null != stmt)  {
        stmt.close();
      }
    }
  }

  protected void runImport(SqoopOptions sqoopOptions, String schema, String tableName)
          throws Exception {
    runImport(sqoopOptions, schema, tableName, new String[0]);
  }

  protected void runImport(SqoopOptions sqoopOptions, String schema,
                           String tableName,  String[] extraArgs) throws Exception {

    sqoopOptions.setTableName(tableName);

    if (schema != null) {
      List<String> extraArgsList = Arrays.asList(new String[]{"--", "--schema", schema});

      if (extraArgs != null) {
        extraArgsList.addAll(0, Arrays.asList(extraArgs));
      }

      extraArgs = extraArgsList.toArray(new String[extraArgsList.size()]);
    }

    Path warehousePath = new Path(LOCAL_WAREHOUSE_DIR);
    Path targetPath = new Path(warehousePath, tableName);

    FileSystem localFs = FileSystem.getLocal(new Configuration());
    if (localFs.exists(targetPath)) {
      localFs.delete(targetPath, true);
    }

    sqoopOptions.setTargetDir(targetPath.toString());

    SqoopTool importTool = new ImportTool();
    Sqoop sqoop = new Sqoop(importTool, sqoopOptions.getConf(), sqoopOptions);
    int ret = Sqoop.runSqoop(sqoop, extraArgs);
    if (0 != ret) {
      throw new Exception("Non-zero return from Sqoop: " + ret);
    }
  }

  /** Fail the test if the files in the tableName directory don't
   * have the expected number of lines.
   */
  protected void verifyImportCount(String tableName, int expectedCount)
      throws IOException {
    Path warehousePath = new Path(LOCAL_WAREHOUSE_DIR);
    Path targetPath = new Path(warehousePath, tableName);

    FileSystem fs = FileSystem.getLocal(new Configuration());
    FileStatus [] files = fs.listStatus(targetPath);

    if (null == files || files.length == 0) {
      assertEquals("Got multiple files; expected none", 0, expectedCount);
    }

    int numLines = 0;
    for (FileStatus stat : files) {
      Path p = stat.getPath();
      if (p.getName().startsWith("part-")) {
        // Found a legit part of the output.
        BufferedReader r = new BufferedReader(
                new InputStreamReader(fs.open(p)));
        try {
          while (null != r.readLine()) {
            numLines++;
          }
        } finally {
          r.close();
        }
      }
    }

    assertEquals("Got unexpected number of lines back", expectedCount,
        numLines);
  }

  /**
   * @return true if the file specified by path 'p' contains 'line'.
   */
  protected boolean checkFileForLine(FileSystem fs, Path p, String line)
      throws IOException {
    BufferedReader r = new BufferedReader(new InputStreamReader(fs.open(p)));
    try {
      while (true) {
        String in = r.readLine();
        if (null == in) {
          break; // done with the file.
        }

        if (line.equals(in)) {
          return true;
        }
      }
    } finally {
      r.close();
    }

    return false;
  }

  /**
   * Returns true if a specific line exists in the import files for the table.
   */
  protected boolean hasImportLine(String tableName, String line)
          throws IOException {
    Path warehousePath = new Path(LOCAL_WAREHOUSE_DIR);
    Path targetPath = new Path(warehousePath, tableName);

    FileSystem fs = FileSystem.getLocal(new Configuration());
    FileStatus [] files = fs.listStatus(targetPath);

    if (null == files || files.length == 0) {
      fail("Got no import files!");
    }

    for (FileStatus stat : files) {
      Path p = stat.getPath();
      if (p.getName().startsWith("part-")) {
        if (checkFileForLine(fs, p, line)) {
          // We found the line. Nothing further to do.
          return true;
        }
      }
    }

    return false;
  }

  /** Verify that a specific line exists in the import files for the table. */
  protected void verifyImportLine(String tableName, String line)
      throws IOException {
    if (!hasImportLine(tableName, line)) {
      fail("Could not find line " + line + " in table " + tableName);
    }
  }

  /**
   * Verify that a specific line has been excluded from the import files for
   * the table.
   */
  protected void verifyMissingLine(String tableName, String line)
      throws IOException {
    if (hasImportLine(tableName, line)) {
      fail("Found unexpected (intentionally excluded) line " + line
          + " in table " + tableName);
    }
  }

  @Test
  public void testBasicDirectImport() throws Exception {
    final String TABLE_NAME = "BASIC_DIRECT_IMPORT";
    createTable(conn, null, TABLE_NAME, "INTEGER", "VARCHAR(32)");
    addRow(conn, null, TABLE_NAME, "1", "'meep'");
    addRow(conn, null, TABLE_NAME, "2", "'beep'");
    addRow(conn, null, TABLE_NAME, "3", "'foo'");
    addRow(conn, null, TABLE_NAME, "4", "'bar'");

    runImport(options, null, TABLE_NAME);
    verifyImportCount(TABLE_NAME, 4);
    verifyImportLine(TABLE_NAME, "1,meep");
    verifyImportLine(TABLE_NAME, "2,beep");
    verifyImportLine(TABLE_NAME, "3,foo");
    verifyImportLine(TABLE_NAME, "4,bar");
  }

  @Test
  public void testDateImport() throws Exception {
    final String TABLE_NAME = "DATE_TABLE";
    createTable(conn, null, TABLE_NAME, "INTEGER", "DATE");
    Date d = new Date(System.currentTimeMillis());
    addRow(conn, null, TABLE_NAME, "1", "'" + d.toString() + "'");

    runImport(options, null, TABLE_NAME);
    verifyImportCount(TABLE_NAME, 1);
    verifyImportLine(TABLE_NAME, "1," + d.toString());
  }

  @Test
  public void testTimeImport() throws Exception {
    final String TABLE_NAME = "TIME_TABLE";
    createTable(conn, null, TABLE_NAME, "INTEGER", "TIME");
    Time t = new Time(System.currentTimeMillis());
    addRow(conn, null, TABLE_NAME, "1", "'" + t.toString() + "'");

    runImport(options, null, TABLE_NAME);
    verifyImportCount(TABLE_NAME, 1);
    verifyImportLine(TABLE_NAME, "1," + t.toString());
  }

  @Test
  public void testTimestampImport() throws Exception {
    final String TABLE_NAME = "TS_TABLE";
    createTable(conn, null, TABLE_NAME, "INTEGER", "TIMESTAMP");
    Timestamp t = new Timestamp(System.currentTimeMillis());
    addRow(conn, null, TABLE_NAME, "1", "'" + t.toString() + "'");

    runImport(options, null, TABLE_NAME);
    verifyImportCount(TABLE_NAME, 1);
    verifyImportLine(TABLE_NAME, "1," + t.toString());
  }

  @Test
  public void testLargeNumber() throws Exception {
    final String TABLE_NAME = "BIGNUM_TABLE";
    createTable(conn, null, TABLE_NAME, "INTEGER", "DECIMAL (30,8)");
    String valStr = "12345678965341.627331";
    addRow(conn, null, TABLE_NAME, "1", valStr);

    runImport(options, null, TABLE_NAME);
    verifyImportCount(TABLE_NAME, 1);
    // import should pad to 8 significant figures after the decimal pt.
    verifyImportLine(TABLE_NAME, "1," + valStr + "00");
  }

  @Test
  public void testEscapedComma() throws Exception {
    final String TABLE_NAME = "COMMA_TABLE";
    options.setEscapedBy('\\');
    createTable(conn, null, TABLE_NAME, "INTEGER", "VARCHAR(32)");
    addRow(conn, null, TABLE_NAME, "1", "'meep,beep'");
    runImport(options, null, TABLE_NAME);
    verifyImportCount(TABLE_NAME, 1);
    verifyImportLine(TABLE_NAME, "1,meep\\,beep");
  }

  @Test
  public void testUserConditions() throws Exception {
    // Test that a user-specified where clause works.

    final String TABLE_NAME = "WHERE_TABLE";
    createTable(conn, null, TABLE_NAME, "INTEGER", "VARCHAR(32)");
    addRow(conn, null, TABLE_NAME, "1", "'foo'");
    addRow(conn, null, TABLE_NAME, "2", "'bar'");
    addRow(conn, null, TABLE_NAME, "3", "'baz'");
    options.setWhereClause("col0 = 2");
    runImport(options, null, TABLE_NAME);
    verifyImportCount(TABLE_NAME, 1);
    verifyImportLine(TABLE_NAME, "2,bar");
    verifyMissingLine(TABLE_NAME, "1,foo");
    verifyMissingLine(TABLE_NAME, "3,baz");
  }

  @Test
  public void testNVarCharImport() throws Exception {
    final String TABLE_NAME = "BASIC_DIRECT_IMPORT";
    createTable(conn, null, TABLE_NAME, "INTEGER", "NVARCHAR(32)");
    addRow(conn, null, TABLE_NAME, "1", "'meep'");
    addRow(conn, null, TABLE_NAME, "2", "'beep'");
    addRow(conn, null, TABLE_NAME, "3", "'foo'");
    addRow(conn, null, TABLE_NAME, "4", "'bar'");

    runImport(options, null, TABLE_NAME);
    verifyImportCount(TABLE_NAME, 4);
    verifyImportLine(TABLE_NAME, "1,meep");
    verifyImportLine(TABLE_NAME, "2,beep");
    verifyImportLine(TABLE_NAME, "3,foo");
    verifyImportLine(TABLE_NAME, "4,bar");
  }

  @Test
  public void testNCharImport() throws Exception {
    final String TABLE_NAME = "NCHAR_TABLE";
    createTable(conn, null, TABLE_NAME, "INTEGER", "NCHAR");
    addRow(conn, null, TABLE_NAME, "1", "'x'");
    addRow(conn, null, TABLE_NAME, "2", "'y'");
    addRow(conn, null, TABLE_NAME, "3", "'z'");

    runImport(options, null, TABLE_NAME);
    verifyImportCount(TABLE_NAME, 3);
    verifyImportLine(TABLE_NAME, "1,x");
    verifyImportLine(TABLE_NAME, "2,y");
    verifyImportLine(TABLE_NAME, "3,z");
  }

  @Test
  public void testUTF8Import() throws Exception {
    final String TABLE_NAME = "NCHAR_TABLE";
    createTable(conn, null, TABLE_NAME, "INTEGER", "NVARCHAR(50)");
    addRow(conn, null, TABLE_NAME, "1", "'žluťoučký kůň'"); // Yellow Horse in Czech

    runImport(options, null, TABLE_NAME);
    verifyImportCount(TABLE_NAME, 1);
    verifyImportLine(TABLE_NAME, "1,žluťoučký kůň"); // Yellow Horse in Czech
  }

  @Test
  public void testDifferentSchemaImport() throws Exception {
    if (NzTestUtil.supportsMultipleSchema(manager.getConnection())) {
      final String SCHEMA_NAME = "IMPORT_SCHEMA";
      final String TABLE_NAME = "SCHEMA_IMPORT";
      createTable(conn, SCHEMA_NAME, TABLE_NAME, "INTEGER", "VARCHAR(32)");
      addRow(conn, SCHEMA_NAME, TABLE_NAME, "1", "'meep'");
      addRow(conn, SCHEMA_NAME, TABLE_NAME, "2", "'beep'");
      addRow(conn, SCHEMA_NAME, TABLE_NAME, "3", "'foo'");
      addRow(conn, SCHEMA_NAME, TABLE_NAME, "4", "'bar'");

      runImport(options, SCHEMA_NAME, TABLE_NAME);
      verifyImportCount(TABLE_NAME, 4);
      verifyImportLine(TABLE_NAME, "1,meep");
      verifyImportLine(TABLE_NAME, "2,beep");
      verifyImportLine(TABLE_NAME, "3,foo");
      verifyImportLine(TABLE_NAME, "4,bar");
    }
  }
}
