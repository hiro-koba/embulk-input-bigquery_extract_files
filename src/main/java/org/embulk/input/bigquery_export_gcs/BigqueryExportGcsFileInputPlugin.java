package org.embulk.input.bigquery_export_gcs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigInject;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.exec.ConfigurableGuessInputPlugin;
import org.embulk.exec.GuessExecutor;
import org.embulk.spi.BufferAllocator;
import org.embulk.spi.Exec;
import org.embulk.spi.FileInputPlugin;
import org.embulk.spi.Schema;
import org.embulk.spi.TransactionalFileInput;
import org.embulk.spi.util.InputStreamTransactionalFileInput;
import org.slf4j.Logger;

import com.google.common.base.Optional;

/**
 * 
 * 
 * 
 * #reference : 
 * 
 * # https://github.com/embulk/embulk
 * # https://github.com/embulk/embulk-input-s3
 * # https://github.com/embulk/embulk-input-gcs
 * # https://github.com/embulk/embulk-input-jdbc
 * # https://github.com/GoogleCloudPlatform/java-docs-samples/blob/master/storage/json-api/src/main/java/StorageSample.java
 * 
 * 
 * @author george 2017. 11. 16.
 *
 */
public class BigqueryExportGcsFileInputPlugin
        implements FileInputPlugin, ConfigurableGuessInputPlugin
{
	private static final Logger log = Exec.getLogger(BigqueryExportGcsFileInputPlugin.class);
	
    public interface PluginTask
            extends Task
    {
        @Config("project")
        public String getProject();

        @Config("json_keyfile")
        public String getJsonKeyfile();
        
        @Config("dataset")
        public String getDataset();
        
        @Config("table")
        @ConfigDefault("null")
        public Optional<String> getTable();
        
        @Config("query")
        @ConfigDefault("null")
        public Optional<String> getQuery();
        
        @Config("cache")
        @ConfigDefault("true")
        public Boolean getQueryCache();
        
        @Config("file_format")
        @ConfigDefault("\"CSV\"")
        public Optional<String> getFileFormat();
        
        @Config("compression")
        @ConfigDefault("\"GZIP\"")
        public Optional<String> getCompression();
        
        @Config("use_legacy_sql")
        @ConfigDefault("false")
        public boolean getUseLegacySql(); 

        @Config("gcs_uri")
        public String getGcsUri();

        @Config("temp_dataset")
        @ConfigDefault("null")
        public Optional<String> getTempDataset();
        public void setTempDataset(String tempDataset);
        
        @Config("temp_local_path")
        //@ConfigDefault("\"/tmp\"")
        public String getTempLocalPath();
        
        @Config("temp_schema_file_path")
        @ConfigDefault("null")
        public Optional<String> getTempSchemaFilePath();
        
        @Config("temp_schema_file_tpye")
        @ConfigDefault("null")
        public Optional<String> getTempSchemaFileType();
        
        public List<String> getFiles();
        public void setFiles(List<String> files);

        @ConfigInject
        public BufferAllocator getBufferAllocator();
        
        public String getGcsBucket();
        public void setGcsBucket(String bucket);
        
        public String getGcsBlobNamePrefix();
        public void setGcsBlobNamePrefix(String blobName);
        
        public String getWorkTable();
        public void setWorkTable(String table);

        public String getTempName();
        public void setTempName(String temp);
        
        //public Schema getSchemaConfig();
        //public void setSchameConfig(SchemaConfig schema);
    }
    
    @Override
	public ConfigDiff guess(ConfigSource execConfig, ConfigSource inputConfig) {

        GuessExecutor guessExecutor = Exec.getInjector().getInstance(GuessExecutor.class);
        return guessExecutor.guessParserConfig(null, inputConfig, execConfig);
	}

	@Override
    public ConfigDiff transaction(ConfigSource config, FileInputPlugin.Control control)
    {
        PluginTask task = config.loadConfig(PluginTask.class);

        processTransactionAndSetTask(task);
        
        int taskCount = task.getFiles().size();

        return resume(task.dump(), taskCount, control);
    }
	
	public void processTransactionAndSetTask(PluginTask task) {

        BigqueryExportUtils.parseGcsUri(task);

        Schema schema = extractBigqueryToGcs(task);
        
        log.info("Schema : {}",schema.toString());
        
        writeSchemaFileIfSpecified(schema, task);
        
        List<String> files = listFilesOfGcs(task);
        
        task.setFiles(files);
        
	}
	
	public void writeSchemaFileIfSpecified(Schema schema, PluginTask task) {
		if(task.getTempSchemaFilePath().isPresent()) {
			log.info("generate temp {} schema file to ... {}", task.getTempSchemaFileType().or(""), task.getTempSchemaFilePath().orNull());
			BigqueryExportUtils.writeSchemaFile(schema, task.getTempSchemaFileType().orNull(), new File(task.getTempSchemaFilePath().get()));
        }
	}
	
    public Schema extractBigqueryToGcs(PluginTask task){
    	try {
    		Schema schema = BigqueryExportUtils.extractBigqueryToGcs(task);
    		return schema;
		} catch (IOException e) {
			log.error("bigquery io error",e);
			return null;
		} catch (InterruptedException e) {
			log.error("bigquery job error",e);
			return null;
		}
    }
    // usually, you have an method to create list of files
    List<String> listFilesOfGcs(PluginTask task)
    {
    	log.info("get file list in to gcs of ... {}.{} -> gs://{}/{}", task.getDataset(), task.getWorkTable(),task.getGcsBucket(),task.getGcsBlobNamePrefix());
    	
    	try {
			return BigqueryExportUtils.getFileListFromGcs(task);
		} catch (IOException e) {
			log.error("gcs error",e);
			return null;
		}
		
    }
    

    @Override
    public ConfigDiff resume(TaskSource taskSource,
            int taskCount,
            FileInputPlugin.Control control)
    {
        control.run(taskSource, taskCount);

        ConfigDiff configDiff = Exec.newConfigDiff();
        //configDiff.has(attrName)
        
        
        // usually, yo uset last_path
        //if (task.getFiles().isEmpty()) {
        //    if (task.getLastPath().isPresent()) {
        //        configDiff.set("last_path", task.getLastPath().get());
        //    }
        //} else {
        //    List<String> files = new ArrayList<String>(task.getFiles());
        //    Collections.sort(files);
        //    configDiff.set("last_path", files.get(files.size() - 1));
        //}

        return configDiff;
    }

    @Override
    public void cleanup(TaskSource taskSource,
            int taskCount,
            List<TaskReport> successTaskReports)
    {
    	final PluginTask task = taskSource.loadTask(PluginTask.class);
    	
    	for(int i=0; i < successTaskReports.size(); i++){
    		TaskReport report = successTaskReports.get(i);
    		if( report.isEmpty() ){
    			String file = task.getFiles().get(i);
    	    	
    			Path p = BigqueryExportUtils.getFullPath(task,file);
    			
    			log.info("delete temp file...{}",p);
    			p.toFile().delete();
    	    	
    			//log.info("delete temp gcs file... {} ... not now", task.getGcsUri());
    			
    			//log.info("delete temp table in bigquery ... {} ... not now", task.getTable());		
    		}else{
    			log.error("datasource not empty : {}", report);
    		}
    	}
    	
    }
    

    @Override
    public TransactionalFileInput open(TaskSource taskSource, int taskIndex)
    {
        final PluginTask task = taskSource.loadTask(PluginTask.class);

        // Write your code here :)
        //throw new UnsupportedOperationException("BigquerycsvFileInputPlugin.open method is not implemented yet");

        // if you expect InputStream, you can use this code:
        
        InputStream input = BigqueryExportUtils.openInputStream(task, task.getFiles().get(taskIndex));
        
        return new InputStreamTransactionalFileInput(task.getBufferAllocator(), input) {
            @Override
            public void abort()
            { }
        
            @Override
            public TaskReport commit()
            {
                return Exec.newTaskReport();
            }
        };
    }
    
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    
}
