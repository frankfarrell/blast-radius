# Blast Radius

Blast radius is a gradle plugin that detects which, if any, modules of a gradle project have 
changed in a deploy-worthy way since the last commit, build or tag. 

So, for example, if you have the following project structure, 
```
root
    - moduleA
    - moduleB
    - moduleC : depends on moduleB
```
A change to the src directory of moduleB will return true for moduleB and moduleC, and false for moduleA and root (`:` by convention). 
If we change a README in moduleA, since that is not deploy-worthy, it returns false for all modules, including root. 

## Why? 

If you are doing continuous deployment, but you do not have all your deployable modules in different repos
with different deployment pipelines, you can use this plugin to limit the blast radius of your deployment. Deploying often is good, but not if there is no change. 

But, even in a single module project you can use this plugin to limit deployments on changes such as to unit tests, documentation etc. 

Note that the plugin isn't too clever, so it will interpret new whitespace in application code as deploy-worthy. 

## How does it work? 

It builds a set of regular expressions from patterns you provided in the configuration and thne does a git diff, matching against files that have changed. 

## Using it

Configuring it: 
```groovy

import com.github.frankfarrell.blastradius.BlastRadiusPlugin

buildscript {   
    repositories {
        jcenter()
    }
    dependencies {
        classpath("com.github.frankfarrell:blast-radius:+")
    }
}

apply plugin: BlastRadiusPlugin
```

You have two options. Standalone or multi module

### Standalone

```groovy
task checkIfAnythingHasChangedTask(type: com.github.frankfarrell.blastradius.ModuleChangedTask){
    filePatterns = ["/[^.]*.gradle", "/src/main/.*"]
}

task migrateLambda(type: AWSLambdaMigrateFunctionTask, dependsOn: [build, checkIfAnythingHasChangedTask]) {
    functionName = "my-first-lambda"
    functionDescription = "Too agile for servers"
    role = "arn:aws:iam::${awsAccountId}:role/some-role"
    zipFile = jar.archivePath
    handler = "com.github.frankfarrell.test.MainHandler"
    memorySize = 512
    runtime = com.amazonaws.services.lambda.model.Runtime.Java8

    doFirst{
        if(!checkIfAnythingHasChangedTask.toDeploy){
            println("Won't deploy")
            throw new StopExecutionException("Won't deploy, no changes")
        }
        else {
            println("Will deploy")
        }
    }


}
```

As you can see ModuleChangedTask has an output variable `toDeploy` which can be used as a boolean in other modules. 

### Multi-module

```groovy
task changedModulesTask(type: com.github.frankfarrell.blastradius.ProjectModulesChangedTask){
    filePatterns = ["/[^.]*.gradle", "/src/main/.*", "/deploy/.*"]
    fileLocation = "${changeFile}"
    moduleFilePatterns = [
            ":": defaultFilePatterns + ["/pipeline/.*", "/buildSrc/[^.]*.gradle", "/buildSrc/src/main/.*"],
            ":terraform" : ["/[^.]*.gradle", "/[^.]*.sh", "/[^.]*.tf", "/[^.]*.tfvars"],
            ":kubernetes-module" : defaultFilePatterns + ["/helm/.*"],
            ":docker-type-module" : ["/[^.]*[.]gradle", "/.*Dockerfile", "/deploy/.*" ],
         ]
}
```
1. filePatterns => Default set of file patterms. Note that these are regular expressions, not unix glob patterns
2. fileLocation => the module will write the results to a file in this location, the file has this format
    ```csv
    :,false
    :terraform,true
    :kubernetes-module,false
    :docker-type-module,true
    ``` 
3. moduleFilePatterns => You can overide the defaults for specific patterns. Examples above are for terraform, kubernetes helm and dockerfiles. 

### DiffStrategy
Either version of the task takes a parameter `diffStrategy` that determines how the diff is done. 
If it fails to find the value, the task returns true for everything (better to dpeloy to much than not to have deployed at all)
1. JENKINS_LAST_COMMIT => Uses the previous git commit from the jenkins build. Watch out for running the same build with different parameters! 
2. PREVIOUS_TAG => If you build with tag 0.1.2, this will do a diff with tag 0.1.1. If you build with HEAD it will compare HEAD with the top commit. Tags must use semantic versioning
3. PREVIOUS_COMMIT => Just compares with the previous commit. 

### Using it from a pipeline

Assuming you have the task configured as above

```groovy

pipeline {
   
    environment {
        CHANGE_FILE = "changeFile"
    }

    stage("Changed Modules"){
        steps{
            sh "./gradlew changedModuleTask -PchangeFile=${env.CHANGE_FILE}"
    
            script {
                changedFiles = readFile("${env.CHANGE_FILE}")
                        .split("\n")
                        .collectEntries{
                            [(it.split(",")[0]): new Boolean(it.split(",")[1])]
                        }
            }
        }
    }
    
    //changedFiles is now a map from module name(always with leading :, where a solo : is the root module) that can be used in following steps
    
    stage("Some other stage"){
         
        when {
            expression {
                return changedFiles[":some-module"] //You may want an elvis operator if there is a chance the module won't be present in the map
            }
        }
        steps{
            //Do stuff
        }
    }
    
}

```