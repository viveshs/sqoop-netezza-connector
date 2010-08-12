// (c) Copyright 2010 Cloudera, Inc. All Rights Reserved.

package com.cloudera.sqoop.netezza;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.mapreduce.lib.db.DBWritable;

import com.cloudera.sqoop.manager.ConnManager;
import com.cloudera.sqoop.manager.ExportJobContext;
import com.cloudera.sqoop.mapreduce.ExportJobBase;
import com.cloudera.sqoop.mapreduce.db.DBConfiguration;
import com.cloudera.sqoop.mapreduce.db.DataDrivenDBInputFormat;

/**
 * Class that runs an export job using remote external tables in the mapper.
 */
public class NetezzaExportJob extends ExportJobBase {

  public static final Log LOG =
      LogFactory.getLog(NetezzaExportJob.class.getName());

  public NetezzaExportJob(final ExportJobContext context) {
    super(context, null, null, NullOutputFormat.class);
  }

  @Override
  /**
   * Configure the inputformat to use for the job.
   */
  protected void configureInputFormat(Job job, String tableName,
      String tableClassName, String splitByCol)
      throws ClassNotFoundException, IOException {

    LOG.info("Using direct Netezza export");

    ConnManager mgr = this.context.getConnManager();
    String username = options.getUsername();
    if (null == username || username.length() == 0) {
      DBConfiguration.configureDB(job.getConfiguration(),
          mgr.getDriverClass(), options.getConnectString());
    } else {
      DBConfiguration.configureDB(job.getConfiguration(),
          mgr.getDriverClass(), options.getConnectString(), username,
          options.getPassword());
    }

    DataDrivenDBInputFormat.setInput(job, DBWritable.class,
        tableName, null, null, new String[0]);

    // Configure the actual InputFormat to use. 
    super.configureInputFormat(job, tableName, tableClassName, splitByCol);
  }


  @Override
  protected Class<? extends Mapper> getMapperClass() {
    if (inputIsSequenceFiles()) {
      return NetezzaRecordExportMapper.class;
    } else {
      return NetezzaTextExportMapper.class;
    }
  }

  @Override
  protected void configureMapper(Job job, String tableName,
      String tableClassName) throws ClassNotFoundException, IOException {
    super.configureMapper(job, tableName, tableClassName);
    job.getConfiguration().set(SQOOP_EXPORT_TABLE_CLASS_KEY, tableClassName);
  }

}