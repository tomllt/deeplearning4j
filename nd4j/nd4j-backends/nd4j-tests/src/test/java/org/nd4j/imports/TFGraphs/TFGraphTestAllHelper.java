/*******************************************************************************
 * Copyright (c) 2015-2018 Skymind, Inc.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

package org.nd4j.imports.TFGraphs;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.nd4j.autodiff.execution.NativeGraphExecutioner;
import org.nd4j.autodiff.execution.conf.ExecutionMode;
import org.nd4j.autodiff.execution.conf.ExecutorConfiguration;
import org.nd4j.autodiff.execution.conf.OutputMode;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.validation.OpValidation;
import org.nd4j.base.Preconditions;
import org.nd4j.imports.graphmapper.tf.TFGraphMapper;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.executioner.OpExecutioner;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.io.ClassPathResource;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.Assert.*;
import static org.nd4j.imports.TFGraphs.TFGraphsSkipNodes.skipNode;

/**
 * Created by susaneraly on 11/6/17.
 */
@Slf4j
public class TFGraphTestAllHelper {


    public enum ExecuteWith {
        SAMEDIFF(SAMEDIFF_DEFAULT_BASE_DIR),
        LIBND4J(LIBND4J_DEFAULT_BASE_DIR),
        JUST_PRINT(COMMON_BASE_DIR);

        private ExecuteWith(String baseDir) {
            this.BASE_DIR = baseDir;
        }

        private final String BASE_DIR;

        public String getDefaultBaseDir() {
            return BASE_DIR;
        }
    }

    @BeforeClass
    public void beforeClass(){
        log.info("Starting tests for class: " + getClass().getName());
    }

    @Before
    public void setup(){
        Nd4j.setDataType(DataBuffer.Type.FLOAT);
    }

    @After
    public void tearDown() throws Exception {
        NativeOpsHolder.getInstance().getDeviceNativeOps().enableDebugMode(false);
        NativeOpsHolder.getInstance().getDeviceNativeOps().enableVerboseMode(false);
    }

    //TODO: Later, we can add this as a param so we can test different graphs in samediff and not samediff
    public static final String COMMON_BASE_DIR = "tf_graphs/examples";
    public static final String SAMEDIFF_DEFAULT_BASE_DIR = COMMON_BASE_DIR;
    public static final String LIBND4J_DEFAULT_BASE_DIR = COMMON_BASE_DIR;

    private static ExecutorConfiguration configuration = ExecutorConfiguration.builder()
            .executionMode(ExecutionMode.SEQUENTIAL)
            .profilingMode(OpExecutioner.ProfilingMode.DISABLED)
            .gatherTimings(true)
            .outputMode(OutputMode.VARIABLE_SPACE)
            .build();

    protected static List<Object[]> fetchTestParams(ExecuteWith executeWith) throws IOException {
        return fetchTestParams(executeWith.getDefaultBaseDir(), executeWith);
    }

    protected static List<Object[]> fetchTestParams(String baseDir, ExecuteWith executeWith) throws IOException {
        String[] modelNames = modelDirNames(baseDir, executeWith);
        List<Object[]> modelParams = new ArrayList<>();
        for (int i = 0; i < modelNames.length; i++) {
            Object[] currentParams = new Object[3];
            currentParams[0] = inputVars(modelNames[i], baseDir); //input variable map - could be null
            currentParams[1] = outputVars(modelNames[i], baseDir); //saved off predictions
            currentParams[2] = modelNames[i];
            modelParams.add(currentParams);
        }
        return modelParams;
    }

    protected static void checkOnlyOutput(Map<String, INDArray> inputs, Map<String, INDArray> predictions, String modelName, ExecuteWith execType, Double precisionOverride) throws IOException {
        log.info("Running model " + modelName + " only output");
        checkOnlyOutput(inputs, predictions, modelName, execType.getDefaultBaseDir(), execType, precisionOverride);
    }

    protected static void checkOnlyOutput(Map<String, INDArray> inputs, Map<String, INDArray> predictions, String modelName, String baseDir, ExecuteWith execType, Double precisionOverride) throws IOException {
        Nd4j.EPS_THRESHOLD = 1e-3;

        SameDiff graph = getGraphAfterExec(baseDir, modelName, inputs, execType);

        //Collect coverage info about ops
        OpValidation.collectTensorflowImportCoverage(graph);

        if (!execType.equals(ExecuteWith.JUST_PRINT)) {
            for (String outputNode : predictions.keySet()) {
                INDArray nd4jPred = null;
                INDArray tfPred = null;

                String nd4jNode = outputNode;

                // we need to convert name from python name format with . on indices, to :. i.e.: output.1 -> output:1
                if (outputNode.contains("."))
                    nd4jNode = outputNode.replaceAll("\\.", ":");

                try {
                    nd4jPred = graph.getVariable(nd4jNode).getArr();
                } catch (NullPointerException e) {
                    throw new NullPointerException("Can't find SameDiff variable with name [" + nd4jNode + "]");
                }

                try {
                    tfPred = predictions.get(outputNode);
                } catch (NullPointerException e) {
                    throw new NullPointerException("Can't find predicted variable with name [" + outputNode + "]");
                }

                assertNotNull(nd4jPred);
                assertNotNull(tfPred);

                if(precisionOverride == null) {
                    assertEquals("Predictions do not match on " + modelName + ", node " + outputNode, tfPred, nd4jPred);
                } else {
                    boolean eq = tfPred.equalsWithEps(nd4jPred, precisionOverride);
                    assertTrue("Predictions do not match on " + modelName + ", node " + outputNode + " - precision " + precisionOverride, eq);
                }
            }
            log.info("\n\tTEST " + modelName + " PASSED...");
            log.info("\n========================================================\n");
        }

        Nd4j.EPS_THRESHOLD = 1e-5;
    }

    public static void checkIntermediate(Map<String, INDArray> inputs, String modelName, ExecuteWith execType) throws IOException {
        checkIntermediate(inputs, modelName, execType.getDefaultBaseDir(), execType);
    }

    public static void checkIntermediate(Map<String, INDArray> inputs, String modelName, String baseDir, ExecuteWith execType) throws IOException {
        Nd4j.EPS_THRESHOLD = 1e-3;
        val graph = getGraphAfterExec(baseDir, modelName, inputs, execType);

        //Collect coverage info about ops
        OpValidation.collectTensorflowImportCoverage(graph);

        if (!execType.equals(ExecuteWith.JUST_PRINT)) {
            for (String varName : graph.variableMap().keySet()) {
                if (!inputs.containsKey(varName)) { //avoiding placeholders
                    INDArray tfValue = intermediateVars(modelName, baseDir, varName);
                    if (tfValue == null) {
                        continue;
                    }
                    if (skipNode(modelName, varName)) {
                        log.info("\n\tFORCING no check on " + varName);
                    } else {
                        assertEquals("Shape not equal on node " + varName, ArrayUtils.toString(tfValue.shape()), ArrayUtils.toString(graph.getVariable(varName).getShape()));
                        assertEquals("Value not equal on node " + varName, tfValue, graph.getVariable(varName).getArr());
                        log.info("\n\tShapes equal for " + varName);
                        log.info("\n\tValues equal for " + varName);
                    }

                }
            }
        }

        Nd4j.EPS_THRESHOLD = 1e-5;
    }

    public static SameDiff getGraphAfterExec(String baseDir, String modelName, Map<String, INDArray> inputs, ExecuteWith executeWith) throws IOException {
        log.info("\n\tRUNNING TEST " + modelName + "...");
        val graph = TFGraphMapper.getInstance().importGraph(new ClassPathResource(baseDir + "/" + modelName + "/frozen_model.pb").getInputStream());
        if (executeWith.equals(ExecuteWith.SAMEDIFF)) {
            if (!inputs.isEmpty()) {
                graph.execWithPlaceHolder(inputs); //This is expected to be just one result
            } else {
                graph.execAndEndResults(); //there are graphs with no placeholders like g_00
            }
        } else if (executeWith.equals(ExecuteWith.LIBND4J)) {
            for (String input : inputs.keySet()) {
                graph.associateArrayWithVariable(inputs.get(input), graph.variableMap().get(input));
            }

//            val string = graph.asFlatPrint();
//            log.info("Graph structure: \n{}", string);
            val executioner = new NativeGraphExecutioner();
            val results = executioner.executeGraph(graph, configuration);

//            graph.asFlatFile(new File("../../../libnd4j/tests_cpu/resources/conv_0.fb"));
        } else if (executeWith.equals(ExecuteWith.JUST_PRINT)) {
            for (String input : inputs.keySet()) {
                graph.associateArrayWithVariable(inputs.get(input), graph.variableMap().get(input));
            }

            val string = graph.asFlatPrint();
            log.info("Graph structure: \n{}", string);
        }
        return graph;
    }

    private static String[] modelDirNames(String base_dir, ExecuteWith executeWith) throws IOException {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(new ClassPathResource(base_dir).getClassLoader());
        Resource[] resources = resolver.getResources("classpath*:" + base_dir + "/**/frozen_model.pb");
        String[] exampleNames = new String[resources.length];
        for (int i = 0; i < resources.length; i++) {
            String nestedName = resources[i].getURL().toString().split(base_dir + "/")[1];
            exampleNames[i] = nestedName.replaceAll(Pattern.quote(executeWith.getDefaultBaseDir()), "").replaceAll("/frozen_model.pb", "");
        }
        return exampleNames;
    }

    protected static Map<String, INDArray> inputVars(String modelName, String base_dir) throws IOException {
        return readVars(modelName, base_dir, "**.placeholder");
    }


    protected static Map<String, INDArray> outputVars(String modelName, String base_dir) throws IOException {
        return readVars(modelName, base_dir, "**.prediction");
    }

    /**
     * Possible for a single node to give multiple outputs
     *
     * How is a node that has a list of outputs like in the case of "node_multiple_out" work
     * Below is hardcoded for a single node
     */
    protected static INDArray intermediateVars(String modelName, String base_dir, String varName) throws IOException {
        //convert varName to convention used in naming files
        // "/" replaced by "____"; followed by a digit indicating the output number followed by prediction_inbw.(shape|csv)
        if (varName.contains(":")) {
            varName = varName.replace(':', '.');
        } else {
            varName = varName + ".0";
        }
        Map<String, INDArray> nodeSepOutput = readVars(modelName, base_dir, varName.replaceAll("/", "____") + ".prediction_inbw");
        //required check for pattern matching as there are scopes and "*" above is a greedy match
        Set<String> removeList = confirmPatternMatch(nodeSepOutput.keySet(), varName);
        for (String toRemove : removeList) {
            nodeSepOutput.remove(toRemove);
        }
        return nodeSepOutput.get(varName); //this *should* return a list of the indarrays for each node
    }

    public static Set<String> confirmPatternMatch(Set<String> setOfNames, String varName) {
        Set<String> removeList = new HashSet<>();
        for (String name : setOfNames) {
            if (name.equals(varName)) continue;
            String[] splitByPeriod = name.split("\\.");
            //not a number - maybe another variable deeper in the same scope
            if (!NumberUtils.isNumber(splitByPeriod[splitByPeriod.length - 1])) {
                removeList.add(name);
            } else if (!String.join(".", Arrays.copyOfRange(splitByPeriod, 0, splitByPeriod.length - 1)).equals(varName)) {
                removeList.add(name);
            }
        }
        return removeList;
    }

    protected static Map<String, INDArray> readVars(String modelName, String base_dir, String pattern) throws IOException {
        Map<String, INDArray> varMap = new HashMap<>();
        String modelDir = base_dir + "/" + modelName;
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(new ClassPathResource(modelDir).getClassLoader());
        Resource[] resources = resolver.getResources("classpath*:" + modelDir + "/" + pattern + ".shape");
        val dtype = Nd4j.dataType();
        for (int i = 0; i < resources.length; i++) {
            String fileName = resources[i].getFilename();
            String varPath = modelDir + "/" + fileName;
            String[] varNameArr = fileName.split("\\.");
            String varName = String.join(".", Arrays.copyOfRange(varNameArr, 0, varNameArr.length - 2));
//            int[] varShape = Nd4j.readNumpy(new ClassPathResource(varPath).getInputStream(), ",").data().asInt();

            List<String> lines = FileUtils.readLines(new ClassPathResource(varPath).getFile(), Charset.forName("UTF-8"));
            List<String> filtered = new ArrayList<>(lines.size());
            for(String s : lines){
                String trimmed = s.trim();
                if(!trimmed.isEmpty()){
                    filtered.add(trimmed);
                }
            }

            INDArray varValue;
            if(filtered.size() == 0){
                //Scalar
                float[] varContents = Nd4j.readNumpy(new ClassPathResource(varPath.replace(".shape", ".csv")).getInputStream(), ",").data().asFloat();
                Preconditions.checkState(varContents.length == 1, "Expected length 1 content for scalar shape; got length %s", varContents.length);
                varValue = Nd4j.trueScalar(varContents[0]);
            } else {
                int[] varShape = new int[filtered.size()];
                for( int j=0; j<filtered.size(); j++ ){
                    varShape[j] = Integer.parseInt(filtered.get(j));
                }
                try {
                    float[] varContents = Nd4j.readNumpy(new ClassPathResource(varPath.replace(".shape", ".csv")).getInputStream(), ",").data().asFloat();
                    if (varShape.length == 1) {
                        if (varShape[0] == 0) {
                            varValue = Nd4j.trueScalar(varContents[0]);
                        } else {
                            varValue = Nd4j.trueVector(varContents);
                        }
                    } else {
                        varValue = Nd4j.create(varContents, varShape);
                    }
                } catch (NumberFormatException e) {
                    // FIXME: we can't parse boolean arrays right now :(
                    log.warn("Error parsing number", e);
                    continue;
                }
            }
            //varValue = Nd4j.readNumpy(new ClassPathResource(varPath.replace(".shape", ".csv")).getInputStream(), ",").reshape(varShape);
            if (varName.contains("____")) {
                //these are intermediate node outputs
                varMap.put(varName.replaceAll("____", "/"), varValue);
            } else {
                varMap.put(varName, varValue);
            }
        }
        return varMap;
    }


    public static Double testPrecisionOverride(String testName){
        if("conv_4".equalsIgnoreCase(testName)){
            //Most values: around 1k. So this is the 6th significant figure, which is OK
            return 1e-2;
        }
        return null;
    }
}
