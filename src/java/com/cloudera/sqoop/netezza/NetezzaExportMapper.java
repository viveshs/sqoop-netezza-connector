// (c) Copyright 2010 Cloudera, Inc. All Rights Reserved.

package com.cloudera.sqoop.netezza;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.mapreduce.Mapper;

import com.cloudera.sqoop.io.NamedFifo;
import com.cloudera.sqoop.lib.DelimiterSet;
import com.cloudera.sqoop.lib.RecordParser;
import com.cloudera.sqoop.lib.SqoopRecord;
import com.cloudera.sqoop.mapreduce.ExportJobBase;
import com.cloudera.sqoop.mapreduce.db.DBConfiguration;
import com.cloudera.sqoop.util.TaskId;

/**
 * Mapper that writes to a named FIFO which will be used to export rows
 * from HDFS to Netezza at high speed.
 *
 * map() methods are actually provided by subclasses that read from
 * SequenceFiles (containing existing SqoopRecords) or text files
 * (containing delimited lines) and deliver these results to the fifo.
 */
public class NetezzaExportMapper<KEYIN, VALIN>
    extends Mapper<KEYIN, VALIN, NullWritable, NullWritable> {

  public static final Log LOG = LogFactory.getLog(
      NetezzaExportMapper.class.getName());

  private Configuration conf;

  /** The FIFO being used to communicate with netezza. */
  private File fifoFile;

  /** The OutputStream we are using to write the fifo data. */
  private OutputStream exportStream;
  
  /** Object that holds/parses a record of the user's input. */
  private SqoopRecord inputRecord;

  /** Delimiters to use for Netezza. */
  private DelimiterSet outputDelimiters;

  private class JdbcThread extends Thread {
    private SQLException sqlException;
    private Connection conn;

    public JdbcThread() {
      this.conn = null;
    }

    public SQLException getException() {
      return sqlException;
    }


    /**
     * Create the connection instance.
     */
    public void initConnection() throws SQLException {
      // Use JDBC to connect to the database.
      DBConfiguration dbConf = new DBConfiguration(conf);
      try {
        conn = dbConf.getConnection();
      } catch (ClassNotFoundException cnfe) {
        throw new SQLException(cnfe);
      }
      if (null == conn) {
        throw new SQLException("Could not connect to database");
      }
    }

    public void run() {
      PreparedStatement ps = null;

      try {
        DBConfiguration dbConf = new DBConfiguration(conf);
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ");
        sb.append(dbConf.getInputTableName());
        sb.append(" SELECT * FROM EXTERNAL '");
        sb.append(NetezzaExportMapper.this.fifoFile.getAbsolutePath());
        sb.append("' USING (REMOTESOURCE 'JDBC' ");
        sb.append("BOOLSTYLE 'TRUE_FALSE' ");
        sb.append("CRINSTRING FALSE ");
        sb.append("DELIMITER ',' ");
        sb.append("ENCODING 'internal' ");
        sb.append("ESCAPECHAR '\\' ");
        sb.append("FORMAT 'text' ");
        sb.append("INCLUDEZEROSECONDS TRUE ");
        sb.append("NULLVALUE 'null' ");
        sb.append(")");

        try {
          ps = conn.prepareStatement(sb.toString());
          ps.executeUpdate();
        } finally {
          if (null != ps) {
            ps.close();
          }
        }
      } catch (SQLException sqlE) {
        // Save this exception for the parent thread to use to fail the task.
        this.sqlException = sqlE;
      } finally {
        if (null != conn) {
          try {
            conn.close();
          } catch (SQLException sqlE) {
            // Exception closing the connection does not fail the task.
            LOG.error("Exception closing connection: " + sqlE);
          }
        }
      }
    }
  }

  /** Thread which executes the SQL query to import over the FIFO. */
  private JdbcThread jdbcThread;

  /**
   * Create a named FIFO, and bind the JDBC connection to the FIFO.
   * A File object representing the FIFO is in 'fifoFile'.
   */
  private void initImportProcess() throws IOException {
    // Create the FIFO where we'll put the data.
    File taskAttemptDir = TaskId.getLocalWorkPath(conf);
    this.fifoFile = new File(taskAttemptDir, "netezza.txt");

    NamedFifo nf = new NamedFifo(this.fifoFile);
    nf.create();

    // Start the JDBC thread which connects to the database
    // and opens the read side of the FIFO.
    this.jdbcThread = new JdbcThread();
    this.jdbcThread.setDaemon(true);
    try {
      this.jdbcThread.initConnection();
    } catch (SQLException sqlE) {
      throw new IOException(sqlE);
    }
    this.jdbcThread.start();

    // Open the write side of the FIFO.
    this.exportStream = new FileOutputStream(nf.getFile());
  }

  @Override
  public void run(Context context) throws IOException, InterruptedException {
    setup(context);
    initImportProcess();
    try {
      while (context.nextKeyValue()) {
        map(context.getCurrentKey(), context.getCurrentValue(), context);
      }
      cleanup(context);
    } finally {
      // Shut down the export process.
      try {
        closeHandles();
      } catch (SQLException sqlE) {
        throw new IOException(sqlE);
      }
    }
  }

  private void closeHandles() throws InterruptedException, SQLException {
    // Try to close the FIFO handle. An exception here does not cause task
    // failure.
    if (null != this.exportStream) {
      try {
        this.exportStream.close();
      } catch (IOException ioe) {
        LOG.warn("Error closing FIFO stream: " + ioe);
      } finally {
        this.exportStream = null;
      }
    }

    // Wait for the JDBC thread to complete processing
    // and stop.
    this.jdbcThread.join();

    SQLException sqlE = this.jdbcThread.getException();
    if (null != sqlE) {
      throw new SQLException(sqlE);
    }
  }

  @Override
  protected void setup(Context context) throws IOException {
    this.conf = context.getConfiguration();
    Class<? extends SqoopRecord> recordClass = (Class<? extends SqoopRecord>)
        this.conf.getClass(ExportJobBase.SQOOP_EXPORT_TABLE_CLASS_KEY, null);
    if (null != recordClass) {
      // Try to instantiate the user's record class.
      this.inputRecord = ReflectionUtils.newInstance(recordClass, conf);
    }

    this.outputDelimiters = new DelimiterSet(',', '\n', (char) 0, '\\', false);
  }

  /**
   * Takes a delimited text record (e.g., the output of a 'Text' object),
   * re-encodes it for consumption by mysqlimport, and writes it to the pipe.
   * @param record A delimited text representation of one record.
   */
  protected void writeRecord(Text record) throws IOException {
    try {
      inputRecord.parse(record);
    } catch (RecordParser.ParseError e) {
      throw new IOException(e);
    }

    writeRecord(inputRecord);
  }

  protected void writeRecord(SqoopRecord r) throws IOException {
    String outputStr = r.toString(outputDelimiters);
    byte [] outputBytes = outputStr.getBytes("UTF-8");
    this.exportStream.write(outputBytes, 0, outputBytes.length);
  }
}
