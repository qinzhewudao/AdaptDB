package perf.benchmark;

import java.io.File;

import org.apache.curator.framework.CuratorFramework;
import org.apache.hadoop.fs.FileSystem;

import core.index.build.BufferedPartitionWriter;
import core.index.build.HDFSPartitionWriter;
import core.index.build.IndexBuilder;
import core.index.build.PartitionWriter;
import core.index.kdtree.KDMedianTree;
import core.index.key.CartilageIndexKey;
import core.index.robusttree.RobustTreeHs;
import core.utils.ConfUtils;
import core.utils.CuratorUtils;
import core.utils.HDFSUtils;

public class RunIndexBuilder {
	String inputFilename;
	CartilageIndexKey key;
	IndexBuilder builder;

	int numPartitions;
	int partitionBufferSize;

	String localPartitionDir;
	String hdfsPartitionDir;

	String propertiesFile;
	int attributes;
	int replication;
	ConfUtils cfg;
	
	public void setUp(){
		inputFilename = BenchmarkSettings.pathToDataset + "lineitem.tbl";
		partitionBufferSize = 5*1024*1024;
		numPartitions = 16;

		propertiesFile = BenchmarkSettings.cartilageConf;
		cfg = new ConfUtils(propertiesFile);
		hdfsPartitionDir = cfg.getHDFS_WORKING_DIR();
		
		key = new CartilageIndexKey('|');
		//key = new SinglePassIndexKey('|');
		builder = new IndexBuilder();

		attributes = 16;
		replication = 3;
	}

	private PartitionWriter getLocalWriter(String partitionDir){
		return new BufferedPartitionWriter(partitionDir, partitionBufferSize, numPartitions);
	}

	private PartitionWriter getHDFSWriter(String partitionDir, short replication){
		return new HDFSPartitionWriter(partitionDir, partitionBufferSize, numPartitions, replication, propertiesFile);
	}

    public void testBuildKDMedianTreeLocal(){
        File f = new File(inputFilename);
        Runtime runtime = Runtime.getRuntime();
        double samplingRate = runtime.freeMemory() / (2.0 * f.length());
        System.out.println("Sampling rate: "+samplingRate);
        builder.build(
                new KDMedianTree(samplingRate),
                key,
                inputFilename,
                getLocalWriter(localPartitionDir)
        );
    }

	public void testBuildKDMedianTreeBlockSamplingOnly(int scaleFactor) {
		int bucketSize = 64; // 64 mb
		int numBuckets = (scaleFactor * 759) / bucketSize + 1;
		System.out.println("Num buckets: "+numBuckets);
		builder.buildWithBlockSamplingDir(0.0002,
				numBuckets,
				new KDMedianTree(1),
				key,
				BenchmarkSettings.pathToDataset + scaleFactor + "/");
	}

	public void testBuildRobustTree(){
		builder.build(new RobustTreeHs(0.01),
						key,
						inputFilename,
						getHDFSWriter(hdfsPartitionDir, (short)replication));
	}

	public void testBuildRobustTreeBlockSampling() {
		ConfUtils cfg = new ConfUtils(BenchmarkSettings.cartilageConf);
		CuratorFramework client = CuratorUtils.createAndStartClient(cfg.getZOOKEEPER_HOSTS());
		CuratorUtils.deleteAll(client, "/", "partition-");
		client.close();
		builder.buildWithBlockSampling(0.0002,
				new RobustTreeHs(1),
				key,
				inputFilename,
				getHDFSWriter(hdfsPartitionDir, (short) replication));
	}

	public void testBuildRobustTreeBlockSamplingOnly(int scaleFactor) {
		int bucketSize = 64; // 64 mb
		int numBuckets = (scaleFactor * 759) / bucketSize + 1;
		builder.buildWithBlockSamplingDir(0.0002,
				numBuckets,
				new RobustTreeHs(1),
				key,
				BenchmarkSettings.pathToDataset + scaleFactor + "/");
	}

	public void testSparkPartitioning() {
		builder.buildWithSpark(0.01,
				new RobustTreeHs(1),
				key,
				inputFilename,
				getHDFSWriter(hdfsPartitionDir, (short) replication),
				BenchmarkSettings.cartilageConf,
				cfg.getHDFS_WORKING_DIR());
	}

	public void testBuildRobustTreeDistributed(String partitionsId){
		ConfUtils conf = new ConfUtils(propertiesFile);
		FileSystem fs = HDFSUtils.getFS(conf.getHADOOP_HOME() + "/etc/hadoop/core-site.xml");
		byte[] indexBytes = HDFSUtils.readFile(fs, hdfsPartitionDir + "/index");
		RobustTreeHs index = new RobustTreeHs(1);
		index.unmarshall(indexBytes);
		builder.buildDistributedFromIndex(index,
				key,
				BenchmarkSettings.pathToDataset,
				getHDFSWriter(hdfsPartitionDir + "/partitions" + partitionsId, (short) replication));
	}

	public void testBuildRobustTreeReplicated(int scaleFactor, int numReplicas){
		int bucketSize = 64; // 64 mb
		int numBuckets = (scaleFactor * 759) / bucketSize + 1;
		ConfUtils cfg = new ConfUtils(BenchmarkSettings.cartilageConf);
		CuratorFramework client = CuratorUtils.createAndStartClient(cfg.getZOOKEEPER_HOSTS());
		CuratorUtils.deleteAll(client, "/", "partition-");
		client.close();
		builder.build(0.0002,
				numBuckets,
				new RobustTreeHs(1),
				key,
				BenchmarkSettings.pathToDataset,
				getHDFSWriter(hdfsPartitionDir, (short) replication),
				attributes,
				numReplicas
		);
	}

	public static void main(String[] args){
		// TODO(anil): Create a single index builder.
		RunIndexBuilder t = new RunIndexBuilder();
		t.setUp();
		t.testBuildRobustTree();
	}
}
