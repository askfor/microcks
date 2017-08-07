/*
 * Licensed to Laurent Broudoux (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.github.lbroudoux.microcks.service;

import com.github.lbroudoux.microcks.domain.*;
import com.github.lbroudoux.microcks.repository.ImportJobRepository;
import com.github.lbroudoux.microcks.repository.RequestRepository;
import com.github.lbroudoux.microcks.repository.ResponseRepository;
import com.github.lbroudoux.microcks.repository.TestResultRepository;
import com.github.lbroudoux.microcks.util.IdBuilder;
import com.github.lbroudoux.microcks.util.UsernamePasswordAuthenticator;
import com.github.lbroudoux.microcks.util.postman.PostmanTestStepsRunner;
import com.github.lbroudoux.microcks.util.soapui.SoapUITestStepsRunner;
import com.github.lbroudoux.microcks.util.test.AbstractTestRunner;
import com.github.lbroudoux.microcks.util.test.HttpTestRunner;
import com.github.lbroudoux.microcks.util.test.SoapHttpTestRunner;
import com.github.lbroudoux.microcks.util.test.TestReturn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Future;

/**
 * @author laurent
 */
@org.springframework.stereotype.Service
public class TestService {

   /** A simple logger for diagnostic messages. */
   private static Logger log = LoggerFactory.getLogger(TestService.class);

   @Autowired
   private RequestRepository requestRepository;

   @Autowired
   private ResponseRepository responseRepository;

   @Autowired
   private TestResultRepository testResultRepository;

   @Autowired
   private ImportJobRepository jobRepository;

   @Value("${network.username}")
   private final String username = null;

   @Value("${network.password}")
   private final String password = null;


   /**
    *
    * @param service
    * @param testEndpoint
    * @param runnerType
    * @return
    */
   public TestResult launchTests(Service service, String testEndpoint, TestRunnerType runnerType){
      TestResult testResult = new TestResult();
      testResult.setTestDate(new Date());
      testResult.setTestedEndpoint(testEndpoint);
      testResult.setServiceId(service.getId());
      testResult.setRunnerType(runnerType);
      testResultRepository.save(testResult);

      // Launch test asynchronously before returning result.
      launchTests(testResult, service, runnerType);
      return testResult;
   }

   /**
    *
    * @param testResultId
    * @param operationName
    * @param testReturns
    * @return
    */
   public TestCaseResult reportTestCaseResult(String testResultId, String operationName, List<TestReturn> testReturns) {
      log.info("Reporting a TestCaseResult for testResult {} on operation '{}'", testResultId, operationName);
      TestResult testResult = testResultRepository.findOne(testResultId);
      TestCaseResult updatedTestCaseResult = null;

      for (TestCaseResult testCaseResult : testResult.getTestCaseResults()) {
         // Find the testCaseResult matching operation name.
         if (testCaseResult.getOperationName().equals(operationName)) {
            updatedTestCaseResult = testCaseResult;
            if (testReturns == null || testReturns.isEmpty()) {
               testCaseResult.setElapsedTime(-1);
               testCaseResult.setSuccess(false);
            } else {
               String testCaseId = IdBuilder.buildTestCaseId(testResult, operationName);
               updateTestCaseResultWithReturns(testCaseResult, testReturns, testCaseId);
            }
         }
      }

      // Finally, Update success, progress indicators and total time before saving and returning.
      updateTestResult(testResult);
      return updatedTestCaseResult;
   }

   /**
    *
    * @param testResult
    * @param service
    * @param runnerType
    * @return
    */
   @Async
   private Future<TestResult> launchTests(TestResult testResult, Service service, TestRunnerType runnerType){
      // Found next build number for this test.
      List<TestResult> older = testResultRepository.findByServiceId(service.getId(), new PageRequest(0, 2, Sort.Direction.DESC, "testNumber"));
      if (older != null && !older.isEmpty() && older.get(0).getTestNumber() != null){
         testResult.setTestNumber(older.get(0).getTestNumber() + 1L);
      } else {
         testResult.setTestNumber(1L);
      }

      for (Operation operation : service.getOperations()){
         // Prepare result container for operation tests.
         TestCaseResult testCaseResult = new TestCaseResult();
         testCaseResult.setOperationName(operation.getName());
         String testCaseId = IdBuilder.buildTestCaseId(testResult, operation);

         // Prepare collection of requests to launch.
         List<Request> requests = requestRepository.findByOperationId(IdBuilder.buildOperationId(service, operation));
         requests = cloneRequestsForTestCase(requests, testCaseId);

         List<TestReturn> results = new ArrayList<TestReturn>();
         AbstractTestRunner<HttpMethod> testRunner = retrieveRunner(runnerType, service.getId());
         try {
            HttpMethod method = testRunner.buildMethod(operation.getMethod());
            results = testRunner.runTest(service, operation, testResult, requests, testResult.getTestedEndpoint(), method);
         } catch (URISyntaxException use) {
            log.error("URISyntaxException on endpoint {}, aborting current tests",
                  testResult.getTestedEndpoint(), use);
            // Set flags and add to results before exiting loop.
            testCaseResult.setSuccess(false);
            testCaseResult.setElapsedTime(0);
            testResult.getTestCaseResults().add(testCaseResult);
            break;
         } catch (Throwable t) {
            log.error("Throwable while testing operation {}", operation.getName(),  t);
         }

         //
         updateTestCaseResultWithReturns(testCaseResult, results, testCaseId);
         testResult.getTestCaseResults().add(testCaseResult);
         testResultRepository.save(testResult);
      }

      // Update success, progress indicators and total time before saving and returning.
      updateTestResult(testResult);

      return new AsyncResult<>(testResult);
   }


   /**
    *
    */
   private void updateTestCaseResultWithReturns(TestCaseResult testCaseResult,
                                     List<TestReturn> testReturns, String testCaseId) {
      // Prepare a bunch of flag we're going to complete.
      boolean successFlag = true;
      long caseElapsedTime = 0;
      List<Response> responses = new ArrayList<Response>();
      List<Request> actualRequests = new ArrayList<Request>();

      for (TestReturn testReturn : testReturns) {
         // Deal with elapsed time and success flag.
         caseElapsedTime += testReturn.getElapsedTime();
         TestStepResult testStepResult = testReturn.buildTestStepResult();
         if (!testStepResult.isSuccess()){
            successFlag = false;
         }

         // Extract, complete and store response and request.
         testReturn.getResponse().setTestCaseId(testCaseId);
         testReturn.getRequest().setTestCaseId(testCaseId);
         responses.add(testReturn.getResponse());
         actualRequests.add(testReturn.getRequest());

         testCaseResult.getTestStepResults().add(testStepResult);
      }

      // Save the responses into repository to get their ids.
      log.debug("Saving {} responses with testCaseId {}", responses.size(), testCaseId);
      responseRepository.save(responses);

      // Associate responses to requests before saving requests.
      for (int i=0; i<actualRequests.size(); i++){
         actualRequests.get(i).setResponseId(responses.get(i).getId());
      }
      log.debug("Saving {} requests with testCaseId {}", responses.size(), testCaseId);
      requestRepository.save(actualRequests);

      // Update and save the completed TestCaseResult.
      // We cannot consider as success if we have no TestStepResults associated...
      if (testCaseResult.getTestStepResults().size() > 0) {
         testCaseResult.setSuccess(successFlag);
      }
      testCaseResult.setElapsedTime(caseElapsedTime);
   }

   /**
    *
    */
   private void updateTestResult(TestResult testResult) {
      // Update success, progress indicators and total time before saving and returning.
      boolean globalSuccessFlag = true;
      boolean globalProgressFlag = false;
      long totalElapsedTime = 0;
      for (TestCaseResult testCaseResult : testResult.getTestCaseResults()){
         totalElapsedTime += testCaseResult.getElapsedTime();
         if (!testCaseResult.isSuccess()){
            globalSuccessFlag = false;
         }
         if (testCaseResult.getElapsedTime() == 0) {
            globalProgressFlag = true;
         }
      }

      // Update aggregated flags before saving whole testResult.
      testResult.setSuccess(globalSuccessFlag);
      testResult.setInProgress(globalProgressFlag);
      testResult.setElapsedTime(totalElapsedTime);

      testResultRepository.save(testResult);
   }

   /** Clone and prepare request for a test case usage. */
   private List<Request> cloneRequestsForTestCase(List<Request> requests, String testCaseId){
      List<Request> result = new ArrayList<Request>();
      for (Request request : requests){
         Request clone = new Request();
         clone.setName(request.getName());
         clone.setContent(request.getContent());
         clone.setHeaders(request.getHeaders());
         clone.setQueryParameters(request.getQueryParameters());
         // Assign testCaseId.
         clone.setTestCaseId(testCaseId);
         result.add(clone);
      }
      return result;
   }

   /** Retrieve correct test runner according given type. */
   private AbstractTestRunner<HttpMethod> retrieveRunner(TestRunnerType runnerType, String serviceId){
      // TODO: remove this ugly initialization later.
      SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
      factory.setConnectTimeout(200);
      factory.setReadTimeout(10000);

      switch (runnerType){
         case SOAP_HTTP:
            SoapHttpTestRunner soapRunner = new SoapHttpTestRunner();
            soapRunner.setClientHttpRequestFactory(factory);
            return soapRunner;
         case SOAP_UI:
            // Handle local download of correct project file.
            List<ImportJob> jobs = jobRepository.findByServiceRefId(serviceId);
            if (jobs != null && !jobs.isEmpty()) {
               try {
                  String projectFile = handleRemoteFileDownload(jobs.get(0).getRepositoryUrl());
                  SoapUITestStepsRunner soapUIRunner = new SoapUITestStepsRunner(projectFile);
                  return soapUIRunner;
               } catch (IOException ioe) {
                  log.error("IOException while downloading {}", jobs.get(0).getRepositoryUrl());
               }
            }
         case POSTMAN:
            // Handle local download of correct project file.
            jobs = jobRepository.findByServiceRefId(serviceId);
            if (jobs != null && !jobs.isEmpty()) {
               try {
                  String collectionFile = handleRemoteFileDownload(jobs.get(0).getRepositoryUrl());
                  PostmanTestStepsRunner postmanRunner = new PostmanTestStepsRunner(collectionFile);
                  postmanRunner.setClientHttpRequestFactory(factory);
                  return postmanRunner;
               } catch (IOException ioe) {
                  log.error("IOException while downloading {}", jobs.get(0).getRepositoryUrl());
               }
            }
         default:
            HttpTestRunner httpRunner = new HttpTestRunner();
            httpRunner.setClientHttpRequestFactory(factory);
            return httpRunner;
      }
   }

   /** Download a remote HTTP URL into a temporary local file. */
   private String handleRemoteFileDownload(String remoteUrl) throws IOException {
      // Build remote Url and local file.
      URL website = new URL(remoteUrl);
      String localFile = System.getProperty("java.io.tmpdir") + "/microcks-" + System.currentTimeMillis() + ".project";
      // Set authenticator instance.
      Authenticator.setDefault(new UsernamePasswordAuthenticator(username, password));
      ReadableByteChannel rbc = null;
      FileOutputStream fos = null;
      try {
         rbc = Channels.newChannel(website.openStream());
         // Transfer file to local.
         fos = new FileOutputStream(localFile);
         fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
      }
      finally {
         if (fos != null)
            fos.close();
         if (rbc != null)
            rbc.close();
      }
      return localFile;
   }
}
