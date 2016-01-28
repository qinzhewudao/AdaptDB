package core.adapt.spark.join;


import core.adapt.JoinQuery;
import core.adapt.Query;
import core.adapt.iterator.IteratorRecord;
import core.adapt.spark.SparkInputFormat;
import core.adapt.spark.SparkQueryConf;
import core.utils.RangePartitionerUtils;
import core.utils.SparkUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import core.utils.ConfUtils;
import org.apache.spark.api.java.function.PairFunction;

import scala.Tuple2;

/**
 * Created by ylu on 1/6/16.
 */


public class SparkJoinQuery {

    public static class Mapper implements PairFunction<Tuple2<LongWritable, Tuple2<Text, Text>>, LongWritable, Text> {
        String Delimiter;
        String Splitter;
        int partitionKey;
        public Mapper(String Delimiter, int partitionKey) {
            this.Delimiter = Delimiter;
            this.partitionKey = partitionKey;
            if (Delimiter.equals("|"))
                Splitter = "\\|";
            else
                Splitter = Delimiter;
        }

        @Override
        public Tuple2<LongWritable, Text> call(Tuple2<LongWritable, Tuple2<Text, Text>> x) throws Exception {
            String s1 = x._2()._1().toString();
            String s2 = x._2()._2().toString();
            String value = s1 + Delimiter + s2;
            int key = Integer.parseInt(value.split(Splitter)[partitionKey]);
            return new Tuple2<LongWritable, Text>(new LongWritable(key), new Text(value));
        }
    }

    protected SparkJoinQueryConf queryConf;
    protected JavaSparkContext ctx;
    protected ConfUtils cfg;

    private String Delimiter = "|";
    private String joinStrategy = "Heuristic";

    public SparkJoinQuery(ConfUtils config) {
        this.cfg = config;
        SparkConf sconf = SparkUtils.getSparkConf(this.getClass().getName(), config);

        try {
            sconf.registerKryoClasses(new Class<?>[]{
                    Class.forName("org.apache.hadoop.io.LongWritable"),
                    Class.forName("org.apache.hadoop.io.Text")
            });
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        ctx = new JavaSparkContext(sconf);
        ctx.hadoopConfiguration().setBoolean(
                FileInputFormat.INPUT_DIR_RECURSIVE, true);
        ctx.hadoopConfiguration().set("fs.hdfs.impl",
                org.apache.hadoop.hdfs.DistributedFileSystem.class.getName());
        queryConf = new SparkJoinQueryConf(ctx.hadoopConfiguration());

        queryConf.setHDFSReplicationFactor(cfg.getHDFS_REPLICATION_FACTOR());
        queryConf.setHadoopHome(cfg.getHADOOP_HOME());
        queryConf.setZookeeperHosts(cfg.getZOOKEEPER_HOSTS());
        queryConf.setMaxSplitSize(1288490188); // 1.2gb
        queryConf.setMinSplitSize(858993459); // 0.8gb


    }

    public void setDelimiter(String delimiter) {
        Delimiter = delimiter;
    }

    public String getDelimiter() {
        return Delimiter;
    }

    public String getCutPoints(String dataset, int attr) {
        int[] cutpoints = {0, 1000000, 2000000, 3000000, 4000000, 5000000};
        return RangePartitionerUtils.getStringCutPoints(cutpoints);
    }

	/* SparkJoinQuery */

    public JavaPairRDD<LongWritable, Text> createJoinRDD(String hdfsPath) {
        queryConf.setJustAccess(true);
        queryConf.setReplicaId(0);
        return ctx.newAPIHadoopFile(cfg.getHADOOP_NAMENODE() + hdfsPath,
                SparkJoinInputFormat.class, LongWritable.class,
                Text.class, ctx.hadoopConfiguration());
    }


    public JavaPairRDD<LongWritable, Text> createJoinScanRDD(
            String dataset1, JoinQuery dataset1_query, String dataset1_cutpoints,
            String dataset2, JoinQuery dataset2_query, String dataset2_cutpoints,
            int partitionKey) {

        // set SparkQuery conf

        String hdfsPath = cfg.getHDFS_WORKING_DIR();

        queryConf.setFullScan(true);
        queryConf.setWorkingDir(hdfsPath);

        Configuration conf = ctx.hadoopConfiguration();

        conf.set("DATASET1", dataset1);
        conf.set("DATASET2", dataset2);

        conf.set("DATASET1_CUTPOINTS", dataset1_cutpoints);
        conf.set("DATASET2_CUTPOINTS", dataset2_cutpoints);

        conf.set("DATASET1_QUERY", dataset1_query.toString());
        conf.set("DATASET2_QUERY", dataset2_query.toString());

        conf.set("PARTITION_KEY", Integer.toString(partitionKey)) ;

        conf.set("JOINALGO", joinStrategy);

        conf.set("DELIMITER", Delimiter);

        JoinPlanner planner = new JoinPlanner(conf);

        // hyper join

        String hyperJoinInput = planner.getHyperJoinInput();

        System.out.println("hyperJoinInput: " + hyperJoinInput);

        conf.set("DATASETINFO",hyperJoinInput);

        JavaPairRDD<LongWritable, Text> hyperjoinRDD = createJoinRDD(hdfsPath);


        // shuffle join

        String input1 = planner.getShuffleJoinInput1();
        String input2 = planner.getShuffleJoinInput2();

        System.out.println("shuffleInput1: " + input1);
        System.out.println("shuffleInput2: " + input2);

        // set conf input;
        conf.set("DATASETFLAG", "1");
        conf.set("DATASETINFO", input1);

        JavaPairRDD<LongWritable, Text> dataset1RDD = createSingleTableScanRDD(hdfsPath, dataset1_query);

        // set conf input;
        conf.set("DATASETFLAG", "2");
        conf.set("DATASETINFO", input2);

        JavaPairRDD<LongWritable, Text> dataset2RDD = createSingleTableScanRDD(hdfsPath, dataset2_query);

        JavaPairRDD<LongWritable, Text> shufflejoinRDD = dataset1RDD.join(dataset2RDD).mapToPair(new Mapper(Delimiter, partitionKey));

        System.out.println("Table 1 count: " + dataset1RDD.count()  + " Table 2 count: " + dataset2RDD.count());

        JavaPairRDD<LongWritable, Text> rdd = hyperjoinRDD.union(shufflejoinRDD);

        return rdd;
    }

    /* SingleTableScan  */

    public JavaPairRDD<LongWritable, Text> createSingleTableScanRDD(String hdfsPath,
                                                               JoinQuery q) {
        queryConf.setWorkingDir(hdfsPath);
        queryConf.setJoinQuery(q);
        queryConf.setJustAccess(true);

        return ctx.newAPIHadoopFile(cfg.getHADOOP_NAMENODE() + hdfsPath + "/" + q.getTable() + "/data",
                SparkScanInputFormat.class, LongWritable.class,
                Text.class, ctx.hadoopConfiguration());
    }
}
