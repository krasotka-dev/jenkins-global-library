#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper
import hudson.FilePath


def runPipeline() {
  def common_docker = new JenkinsDeployerPipeline()
  def environment = ""
  def branch = "${scm.branches[0].name}".replaceAll(/^\*\//, '').replace("/", "-").toLowerCase()
  def k8slabel = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  def deploymentName = "${JOB_NAME}"
                        .split('/')[0]
                        .replace('-fuchicorp', '')
                        .replace('-build', '')
                        .replace('-deploy', '')

  switch(branch) {
    case 'master': environment = 'prod'
    break

    case 'qa': environment = 'qa'
    break

    case 'dev': environment = 'dev'
    break

    case 'tools': environment = 'tools'
    break

    default:
        currentBuild.result = 'FAILURE'
        print('This branch does not supported')
  }

  try {
    properties([
      parameters([
        booleanParam(defaultValue: false,
          description: 'Click this if you would like to deploy to latest',
          name: 'PUSH_LATEST'
          )])])

      def slavePodTemplate = """
      metadata:
        labels:
          k8s-label: ${k8slabel}
        annotations:
          jenkinsjoblabel: ${env.JOB_NAME}-${env.BUILD_NUMBER}
      spec:
        affinity:
          podAntiAffinity:
            requiredDuringSchedulingIgnoredDuringExecution:
            - labelSelector:
                matchExpressions:
                - key: component
                  operator: In
                  values:
                  - jenkins-jenkins-master
              topologyKey: "kubernetes.io/hostname"
        containers:
        - name: docker
          image: docker:latest
          imagePullPolicy: Always
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /var/run/docker.sock
              name: docker-sock
            - mountPath: /etc/secrets/service-account/
              name: google-service-account
        - name: fuchicorptools
          image: fuchicorp/buildtools
          imagePullPolicy: Always
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /var/run/docker.sock
              name: docker-sock
            - mountPath: /etc/secrets/service-account/
              name: google-service-account
        serviceAccountName: common-service-account
        securityContext:
          runAsUser: 0
          fsGroup: 0
        volumes:
          - name: google-service-account
            secret:
              secretName: fuchicorp-service-account
          - name: docker-sock
            hostPath:
              path: /var/run/docker.sock
    """

  podTemplate(name: k8slabel, label: k8slabel, yaml: slavePodTemplate) {
      node(k8slabel) {
        container('fuchicorptools') {
          stage('Build docker image') {

              // Build the docker image
              dockerImage = docker.build(repositoryName, "--build-arg branch_name=${branch} .")
          }

          stage('Push image') {

             // Push image to the Nexus with new release
              docker.withRegistry('https://docker.fuchicorp.com', 'nexus-docker-creds') {
                  dockerImage.push("0.${BUILD_NUMBER}")
                  // messanger.sendMessage("slack", "SUCCESS", slackChannel)


                  if (params.PUSH_LATEST) {
                    // messanger.sendMessage("slack", "PUSHED", slackChannel,
                    """############### This Job pushed to latest version. ###############""".stripIndent())
                    dockerImage.push("latest")
                }
              }
           }
         }
      }
    }
  } catch (e) {
    currentBuild.result = 'FAILURE'
    println("ERROR Detected:")
    println(e.getMessage())
  }
}



return this