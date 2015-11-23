/*
 * Elasticsearch Indexer for ClueWeb09/12 using Hadoop MapReduce.
 * Based on ClueWeb Tools <https://github.com/lintool/clueweb>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package de.webis.app;

import de.webis.clueweb.mapreduce.ClueWebWarcMapper;
import org.apache.commons.cli.*;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.MapFileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import de.webis.clueweb.mapreduce.ClueWeb09InputFormat;
import de.webis.clueweb.mapreduce.ClueWeb12InputFormat;

import java.util.Arrays;
import java.util.List;

/**
 * Generic MapFile generator for web corpora.
 *
 * @author Janek Bevendorff
 * @version 1
 */
public class MapFileGenerator extends Configured implements Tool
{
    public static final String[] UUID_PREFIX_OPTION  = {"prefix", "p"};
    public static final String[] INPUT_OPTION        = {"input", "i"};
    public static final String[] INPUT_FORMAT_OPTION = {"format", "f"};
    public static final String[] OUTPUT_OPTION       = {"output", "o"};

    private static final Logger LOG = Logger.getLogger(MapFileGenerator.class);

    private static final List<String> SUPPORTED_INPUT_FORMATS = Arrays.asList(
            "clueweb09",
            "clueweb12"
    );

    private static Class<? extends InputFormat> getInputFormatClass(final String format)
    {
        if (format.equals("clueweb09")) {
            return ClueWeb09InputFormat.class;
        } else if (format.equals("clueweb12")) {
            return ClueWeb12InputFormat.class;
        } else {
                throw new RuntimeException("Unsupported input format '" + format + "'");
        }
    }

    private static Class<? extends Mapper> getMapperClass(final String format)
    {
        if (format.equals("clueweb09") || format.equals("clueweb12")) {
                return ClueWebWarcMapper.class;
        } else {
                throw new RuntimeException("Unsupported input format '" + format + "'");
        }
    }

    /**
     * Run this tool.
     */
    @Override @SuppressWarnings("static-access")
    public int run(String[] args) throws Exception
    {
        final Options options = new Options();
        options.addOption(OptionBuilder.
                withArgName("PREFIX").
                hasArg().
                withLongOpt(UUID_PREFIX_OPTION[0]).
                withDescription("Prefix to use for UUID generation").
                isRequired().
                create(UUID_PREFIX_OPTION[1]));
        options.addOption(OptionBuilder.
                withArgName("INPUT_FORMAT").
                hasArg().
                withLongOpt(INPUT_FORMAT_OPTION[0]).
                withDescription("Input format for reading the corpus (e.g. clueweb, clueweb12, ...)").
                isRequired().
                create(INPUT_FORMAT_OPTION[1]));
        options.addOption(OptionBuilder.
                withArgName("PATH").
                hasArg().
                withLongOpt(INPUT_OPTION[0]).
                withDescription("Input corpus path").
                isRequired().
                create(INPUT_OPTION[1]));
        options.addOption(OptionBuilder.
                withArgName("PATH").
                hasArg().
                withLongOpt(OUTPUT_OPTION[0]).
                withDescription("Output MapFile").
                isRequired().
                create(OUTPUT_OPTION[1]));

        CommandLine cmdline;
        final CommandLineParser parser = new GnuParser();
        try {
            cmdline = parser.parse(options, args);
        } catch (ParseException exp) {
            final HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp(this.getClass().getSimpleName(), options);
            ToolRunner.printGenericCommandUsage(System.out);
            System.err.println("Error parsing command line: " + exp.getMessage());
            return -1;
        }

        final String uuidPrefix  = cmdline.getOptionValue(UUID_PREFIX_OPTION[0]);
        final String inputPath  = cmdline.getOptionValue(INPUT_OPTION[0]);
        final String inputFormat = cmdline.getOptionValue(INPUT_FORMAT_OPTION[0]);
        final String outputPath  = cmdline.getOptionValue(OUTPUT_OPTION[0]);

        if (!SUPPORTED_INPUT_FORMATS.contains(inputFormat)) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp(this.getClass().getSimpleName(), options);
            ToolRunner.printGenericCommandUsage(System.out);
            System.err.printf("Argument error: Input format '%s' is not supported.\nSupported input formats are: %s\n",
                    inputFormat, StringUtils.join(SUPPORTED_INPUT_FORMATS, ", "));
            return -1;
        }

        LOG.info("Tool name: " + MapFileGenerator.class.getSimpleName());
        LOG.info(" - prefix: "  + uuidPrefix);
        LOG.info(" - input: "   + inputPath);
        LOG.info(" - format: "  + inputFormat);
        LOG.info(" - output: "  + outputPath);

        // configure Hadoop for Elasticsearch
        final Configuration conf = getConf();

        //conf.setBoolean("mapreduce.map.speculative", false);
        //conf.setBoolean("mapreduce.reduce.speculative", false);
        //conf.setBoolean("mapred.map.tasks.speculative.execution", false);
        //conf.setBoolean("mapred.reduce.tasks.speculative.execution", false);
        conf.set("mapfile.uuid.prefix", uuidPrefix);

        final Job job = Job.getInstance(conf);
        job.setJobName(String.format("mapfile-generator-%s", inputFormat));
        job.setJarByClass(MapFileGenerator.class);
        job.setOutputFormatClass(MapFileOutputFormat.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        job.setInputFormatClass(getInputFormatClass(inputFormat));
        job.setMapperClass(getMapperClass(inputFormat));

        FileInputFormat.setInputPaths(job, inputPath);
        MapFileOutputFormat.setOutputPath(job, new Path(outputPath));

        job.waitForCompletion(true);

        return 0;
    }
    /**
     * Dispatches command-line arguments to the tool via the <code>ToolRunner</code>.
     */
    public static void main(String[] args) throws Exception
    {
        LOG.info("Running " + MapFileGenerator.class.getSimpleName() + " with args "
                + Arrays.toString(args));
        System.exit(ToolRunner.run(new MapFileGenerator(), args));
    }
}