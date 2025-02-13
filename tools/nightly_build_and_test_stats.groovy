/*
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

import groovy.json.JsonSlurper
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

node ("master") {
  def jenkinsUrl = "${params.JENKINS_URL}"
  def trssUrl    = "${params.TRSS_URL}"
  def slackChannel = "${params.SLACK_CHANNEL}"

  def buildFailures = 0
  def testStats = []

  // Get the number of "Failing Builds"
  stage("getBuildFailures") {
    def builds = sh(returnStdout: true, script: "wget -q -O - ${jenkinsUrl}/view/Failing%20Builds/api/json")
    def json = new JsonSlurper().parseText(builds)
    buildFailures = json.jobs.size()
  }

  // Get the last Nightly test job & case stats
  stage("getTestStats") {
    // Get top level builds names
    def trssBuildNames = sh(returnStdout: true, script: "wget -q -O - ${trssUrl}/api/getTopLevelBuildNames?type=Test")
    def buildNamesJson = new JsonSlurper().parseText(trssBuildNames)
    buildNamesJson.each { build ->
      // Is it a build Pipeline?
      if (build._id.buildName.contains("-pipeline")) {
        echo "Pipeline ${build._id.buildName}"
        def pipelineName = build._id.buildName

        // Find the last "Done" pipeline builds started by "timer", as that is the last Nightly
        // or upstream project "build-scripts/weekly-openjdkNN-pipeline" started in the last 7 days, as those are weekend weekly release jobs
        def pipeline = sh(returnStdout: true, script: "wget -q -O - ${trssUrl}/api/getBuildHistory?buildName=${pipelineName}")
        def pipelineJson = new JsonSlurper().parseText(pipeline)
        def foundNightly = false
        if (pipelineJson.size() > 0) {
          // Find first in list started by timer(Nightly) or all upstream weekly jobs started in last 7 days
          pipelineJson.each { job ->
            if (!foundNightly) {
              def pipeline_id = null
              def pipelineUrl
              def testJobSuccess = 0
              def testJobUnstable = 0
              def testJobFailure = 0
              def testCasePassed = 0
              def testCaseFailed = 0
              def testCaseDisabled = 0
              def testJobNumber = 0
              def buildJobNumber = 0
              if (job.status != null && job.status.equals("Done") && job.startBy != null) {
                if (job.startBy.startsWith("timer")) {
                  pipeline_id = job._id
                  pipelineUrl = job.buildUrl
                  foundNightly = true
                } else if (job.startBy.startsWith("upstream project \"build-scripts/weekly-${pipelineName}\"")) {
                  // Weekend weekly, was it started in last 7 days?
                  def build_time = LocalDateTime.ofInstant(Instant.ofEpochMilli(job.timestamp), ZoneId.of("UTC"))
                  def now = LocalDateTime.now(ZoneId.of("UTC"))
                  def days = ChronoUnit.DAYS.between(build_time, now)
                  if (days < 7) { 
                    pipeline_id = job._id
                    pipelineUrl = job.buildUrl
                  }
                }
              }
              // Was job a "match"?
              if (pipeline_id != null) {
                // Get all child Test jobs for this pipeline job
                def pipelineTestJobs = sh(returnStdout: true, script: "wget -q -O - ${trssUrl}/api/getAllChildBuilds?parentId=${pipeline_id}\\&buildNameRegex=^Test_.*")
                def pipelineTestJobsJson = new JsonSlurper().parseText(pipelineTestJobs)
                if (pipelineTestJobsJson.size() > 0) {
                  testJobNumber = pipelineTestJobsJson.size()
                  pipelineTestJobsJson.each { testJob ->
                    if (testJob.buildResult.equals("SUCCESS")) {
                      testJobSuccess += 1
                    } else if (testJob.buildResult.equals("UNSTABLE")) {
                      testJobUnstable += 1
                    } else {
                      testJobFailure += 1
                    }
                    if (testJob.testSummary != null) {
                      testCasePassed += testJob.testSummary.passed
                      testCaseFailed += testJob.testSummary.failed
                      testCaseDisabled += testJob.testSummary.disabled
                    }
                  }
                }
                // Get all child Build jobs for this pipeline job
                def pipelineBuildJobs = sh(returnStdout: true, script: "wget -q -O - ${trssUrl}/api/getAllChildBuilds?parentId=${pipeline_id}\\&buildNameRegex=^jdk.*")
                def pipelineBuildJobsJson = new JsonSlurper().parseText(pipelineBuildJobs)
                buildJobNumber = pipelineBuildJobsJson.size()
                def testResult = [name: pipelineName, url: pipelineUrl, buildJobNumber: buildJobNumber,
                          testJobSuccess:   testJobSuccess,
                          testJobUnstable:  testJobUnstable,
                          testJobFailure:   testJobFailure,
                          testCasePassed:   testCasePassed,
                          testCaseFailed:   testCaseFailed,
                          testCaseDisabled: testCaseDisabled,
                          testJobNumber:    testJobNumber]
                testStats.add(testResult)
              }
            }
          }
        }
      }
    }
  }

  // Print the results
  stage("printResults") {
    echo "==================================================================================="
    echo "Build Failures = ${buildFailures}"
    echo "==================================================================================="
    def nightlyTestSuccessRating = 0
    def numTestPipelines = 0
    def totalBuildJobs = 0
    def totalTestJobs = 0
    testStats.each { pipeline ->
      echo "Pipeline : ${pipeline.name} : ${pipeline.url}"
      echo "  => Number of Build jobs = ${pipeline.buildJobNumber}"
      echo "  => Number of Test jobs = ${pipeline.testJobNumber}" 
      echo "  => Test job SUCCESS    = ${pipeline.testJobSuccess}"
      echo "  => Test job UNSTABLE   = ${pipeline.testJobUnstable}"
      echo "  => Test job FAILURE    = ${pipeline.testJobFailure}"
      echo "  => Test case Passed    = ${pipeline.testCasePassed}"
      echo "  => Test case Failed    = ${pipeline.testCaseFailed}"
      echo "  => Test case Disabled  = ${pipeline.testCaseDisabled}"
      echo "==================================================================================="
      totalBuildJobs += pipeline.buildJobNumber
      totalTestJobs += pipeline.testJobNumber
      // Did tests run? (build may have failed)
      if (pipeline.testJobNumber > 0) {
        numTestPipelines += 1
        // Pipeline Test % success rating: Failure twice as signficant as a Success, Unstable counts as -1/4
        nightlyTestSuccessRating += (((pipeline.testJobSuccess)-(pipeline.testJobUnstable*0.25)-(pipeline.testJobFailure*2))*100)/(pipeline.testJobNumber)
      }
    }
    // Average test success rating across all pipelines
    if (numTestPipelines > 0) {
      nightlyTestSuccessRating = nightlyTestSuccessRating/numTestPipelines
    } else {
      // If no Tests were run assume 0% success
      nightlyTestSuccessRating = 0
    }

    // Build % success rating: Successes as % of build total
    def buildSuccesses = totalBuildJobs - buildFailures
    def nightlyBuildSuccessRating = 0
    if (totalBuildJobs > 0) {    
      nightlyBuildSuccessRating = ((buildSuccesses)*100)/(totalBuildJobs)
    } else {
      // If no Builds were run assume 0% success
      nightlyBuildSuccessRating = 0
    }

    // Overall % success rating: Average build & test % success rating
    def overallNightlySuccessRating = ((nightlyBuildSuccessRating+nightlyTestSuccessRating)/2).intValue()

    echo "======> Total number of Build jobs    = ${totalBuildJobs}"
    echo "======> Total number of Test jobs     = ${totalTestJobs}" 
    echo "======> Nightly Build Success Rating  = ${nightlyBuildSuccessRating.intValue()} %"
    echo "======> Nightly Test Success Rating   = ${nightlyTestSuccessRating.intValue()} %"
    echo "======> Overall Nightly Build & Test Success Rating = ${overallNightlySuccessRating} %"

    // Slack message:
    slackSend(channel: slackChannel, color: 'good', message: 'AdoptOpenJDK Jenkins Nightly Build & Test Pipeline Success Rating: '+overallNightlySuccessRating+' % (derived from '+totalBuildJobs+' at '+nightlyBuildSuccessRating.intValue()+' %, '+totalTestJobs+' at '+nightlyTestSuccessRating.intValue()+' %)')
  }
}

