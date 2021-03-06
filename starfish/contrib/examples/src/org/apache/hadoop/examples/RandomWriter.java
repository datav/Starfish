/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.examples;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.ClusterStatus;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobTracker;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * This program uses map/reduce to just run a distributed job where there is no
 * interaction between the tasks and each task write a large unsorted random
 * binary sequence file of BytesWritable. In order for this program to generate
 * data for terasort with 10-byte keys and 90-byte values, have the following
 * config: <xmp> <?xml version="1.0"?> <?xml-stylesheet type="text/xsl"
 * href="configuration.xsl"?> <configuration> <property>
 * <name>test.randomwrite.min_key</name> <value>10</value> </property>
 * <property> <name>test.randomwrite.max_key</name> <value>10</value>
 * </property> <property> <name>test.randomwrite.min_value</name>
 * <value>90</value> </property> <property>
 * <name>test.randomwrite.max_value</name> <value>90</value> </property>
 * <property> <name>test.randomwrite.total_bytes</name>
 * <value>1099511627776</value> </property> </configuration></xmp>
 * 
 * Equivalently, {@link RandomWriter} also supports all the above options and
 * ones supported by {@link GenericOptionsParser} via the command-line.
 */
public class RandomWriter extends Configured implements Tool {

	/**
	 * User counters
	 */
	static enum Counters {
		RECORDS_WRITTEN, BYTES_WRITTEN
	}

	/**
	 * A custom input format that creates virtual inputs of a single string for
	 * each map.
	 */
	static class RandomInputFormat extends InputFormat<Text, Text> {

		@Override
		public RecordReader<Text, Text> createRecordReader(InputSplit split,
				TaskAttemptContext context) throws IOException,
				InterruptedException {
			return new RandomRecordReader(split);
		}

		/**
		 * Generate the requested number of file splits, with the filename set
		 * to the filename of the output file.
		 */
		@Override
		public List<InputSplit> getSplits(JobContext context)
				throws IOException, InterruptedException {

			int numSplits = context.getConfiguration().getInt(
					"random.writer.map.tasks", 1);
			List<InputSplit> splits = new ArrayList<InputSplit>(numSplits);
			Path outDir = FileOutputFormat.getOutputPath(context);
			for (int i = 0; i < numSplits; ++i) {
				splits.add(new FileSplit(new Path(outDir, "dummy-split-" + i),
						0, 1, (String[]) null));
			}
			return splits;
		}

		/**
		 * Return a single record (filename, "") where the filename is taken
		 * from the file split.
		 */
		static class RandomRecordReader extends RecordReader<Text, Text> {

			private Text path = null;
			private Text empty = null;
			private boolean flag = false;

			public RandomRecordReader(InputSplit split) {
				path = new Text(((FileSplit) split).getPath().getName());
				empty = new Text();
				flag = true;
			}

			@Override
			public void close() throws IOException {
			}

			@Override
			public Text getCurrentKey() throws IOException,
					InterruptedException {
				return path;
			}

			@Override
			public Text getCurrentValue() throws IOException,
					InterruptedException {
				return empty;
			}

			@Override
			public float getProgress() throws IOException, InterruptedException {
				return 0;
			}

			@Override
			public void initialize(InputSplit split, TaskAttemptContext context)
					throws IOException, InterruptedException {
			}

			@Override
			public boolean nextKeyValue() throws IOException,
					InterruptedException {
				if (flag) {
					flag = false;
					return true;
				}

				return false;
			}
		}

	}

	static class Map
			extends
			Mapper<WritableComparable<?>, Writable, BytesWritable, BytesWritable> {

		private long numBytesToWrite;
		private int minKeySize;
		private int keySizeRange;
		private int minValueSize;
		private int valueSizeRange;
		private Random random = new Random();
		private BytesWritable randomKey = new BytesWritable();
		private BytesWritable randomValue = new BytesWritable();

		private void randomizeBytes(byte[] data, int offset, int length) {
			for (int i = offset + length - 1; i >= offset; --i) {
				data[i] = (byte) random.nextInt(256);
			}
		}

		/**
		 * Given an output filename, write a bunch of random records to it.
		 */
		@Override
		public void map(WritableComparable<?> key, Writable value,
				Context context) throws IOException, InterruptedException {
			// public void map(WritableComparable key, Writable value,
			// OutputCollector<BytesWritable, BytesWritable> output,
			// Reporter reporter) throws IOException {
			int itemCount = 0;
			while (numBytesToWrite > 0) {
				int keyLength = minKeySize
						+ (keySizeRange != 0 ? random.nextInt(keySizeRange) : 0);
				randomKey.setSize(keyLength);
				randomizeBytes(randomKey.getBytes(), 0, randomKey.getLength());
				int valueLength = minValueSize
						+ (valueSizeRange != 0 ? random.nextInt(valueSizeRange)
								: 0);
				randomValue.setSize(valueLength);
				randomizeBytes(randomValue.getBytes(), 0, randomValue
						.getLength());
				context.write(randomKey, randomValue);
				numBytesToWrite -= keyLength + valueLength;
				context.getCounter(Counters.BYTES_WRITTEN).increment(
						keyLength + valueLength);
				context.getCounter(Counters.RECORDS_WRITTEN).increment(1);

				if (++itemCount % 200 == 0) {
					context.setStatus("wrote record " + itemCount + ". "
							+ numBytesToWrite + " bytes left.");
				}
			}

			context.setStatus("done with " + itemCount + " records.");
		}

		/**
		 * Save the values out of the configuration that we need to write the
		 * data.
		 */
		@Override
		public void setup(Context context) throws IOException,
				InterruptedException {

			Configuration conf = context.getConfiguration();
			numBytesToWrite = conf.getLong("test.randomwrite.bytes_per_map",
					1 * 1024 * 1024 * 1024);
			minKeySize = conf.getInt("test.randomwrite.min_key", 10);
			keySizeRange = conf.getInt("test.randomwrite.max_key", 1000)
					- minKeySize;
			minValueSize = conf.getInt("test.randomwrite.min_value", 0);
			valueSizeRange = conf.getInt("test.randomwrite.max_value", 20000)
					- minValueSize;
		}

	}

	/**
	 * This is the main routine for launching a distributed random write job. It
	 * runs 10 maps/node and each node writes 1 gig of data to a DFS file. The
	 * reduce doesn't do anything.
	 * 
	 * @throws IOException
	 */
	public int run(String[] args) throws Exception {

		if (args.length == 0) {
			System.out.println("Usage: writer <out-dir>");
			ToolRunner.printGenericCommandUsage(System.out);
			return -1;
		}

		Path outDir = new Path(args[0]);
		Job job = new Job(getConf(), "random-writer");

		job.setJarByClass(RandomWriter.class);
		FileOutputFormat.setOutputPath(job, outDir);

		job.setOutputKeyClass(BytesWritable.class);
		job.setOutputValueClass(BytesWritable.class);

		job.setInputFormatClass(RandomInputFormat.class);
		job.setMapperClass(Map.class);
		job.setOutputFormatClass(TextOutputFormat.class);

		// Get the cluster information
		JobClient client = null;
		ClusterStatus cluster = null;
		Configuration conf = job.getConfiguration();
		try {
			client = new JobClient(JobTracker.getAddress(conf), conf);
			cluster = client.getClusterStatus(false);
		} catch (IOException e) {
			e.printStackTrace();
			return -3;
		}

		int numMapsPerHost = conf.getInt("test.randomwriter.maps_per_host", 10);
		long numBytesToWritePerMap = conf.getLong(
				"test.randomwrite.bytes_per_map", 1 * 1024 * 1024 * 1024);
		if (numBytesToWritePerMap == 0) {
			System.err
					.println("Cannot have test.randomwrite.bytes_per_map set to 0");
			return -2;
		}
		long totalBytesToWrite = conf.getLong("test.randomwrite.total_bytes",
				numMapsPerHost * numBytesToWritePerMap
						* cluster.getTaskTrackers());
		int numMaps = (int) (totalBytesToWrite / numBytesToWritePerMap);
		if (numMaps == 0 && totalBytesToWrite > 0) {
			numMaps = 1;
			conf.setLong("test.randomwrite.bytes_per_map", totalBytesToWrite);
		}

		conf.setInt("mapred.map.tasks", numMaps);
		conf.setInt("random.writer.map.tasks", numMaps);
		System.out.println("Running " + numMaps + " maps.");

		// reducer NONE
		job.setNumReduceTasks(0);

		Date startTime = new Date();
		System.out.println("Job started: " + startTime);
		job.waitForCompletion(true);
		Date endTime = new Date();
		System.out.println("Job ended: " + endTime);
		System.out.println("The job took "
				+ (endTime.getTime() - startTime.getTime()) / 1000
				+ " seconds.");

		return 0;
	}

	public static void main(String[] args) throws Exception {
		int res = ToolRunner.run(new Configuration(), new RandomWriter(), args);
		System.exit(res);
	}

}
