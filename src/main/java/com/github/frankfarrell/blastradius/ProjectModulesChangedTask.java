package com.github.frankfarrell.blastradius;

import com.github.frankfarrell.blastradius.DiffStrategy;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.gradle.api.Project;
import org.gradle.api.internal.ConventionTask;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

/**
 * Created by frankfarrell on 13/10/2017.
 *
 * Does a git diff with previous deploy or version
 * Writes a newline separated list of changed modules to a file
 * This file can be read and used by other processes as needed
 *
 * Ideally for use on the root project, but it ought to be able to be used anywhere
 */
public class ProjectModulesChangedTask  extends ConventionTask {

    private static final Logger logger = Logging.getLogger(ProjectModulesChangedTask.class);

    public static final String DEFAULT_FILE_LOCATION = "changedFiles";
    public static final Set<String> DEFAULT_FILE_PATTERNS =  Collections.unmodifiableSet(Stream.of("/[^.]*.gradle", "/src/main/.*").collect(Collectors.toSet()));

    private DiffStrategy diffStrategy = DiffStrategy.JENKINS_LAST_COMMIT;

    private Optional<String> fileLocation = Optional.empty();
    private Optional<Set<String>> filePatterns = Optional.empty();

    private Map<String, List<String>> moduleFilePatterns = new HashMap<>();

    public String getFileLocation() {
        return fileLocation.orElse(DEFAULT_FILE_LOCATION);
    }

    public void setDiffStrategy(final String diffStrategy) {
        this.diffStrategy = DiffStrategy.valueOf(diffStrategy);
    }

    public void setFileLocation(final String fileLocation) {
        this.fileLocation = Optional.ofNullable(fileLocation);
    }

    public Set<String> getFilePatterns() {
        return filePatterns.orElse(DEFAULT_FILE_PATTERNS);
    }

    public void setFilePatterns(final List<String> filePatterns) {
        this.filePatterns = filePatterns == null? Optional.empty():Optional.of(new HashSet<>(filePatterns));
    }

    public Map<String, Set<String>> getModuleFilePatterns() {
        return moduleFilePatterns.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> new HashSet<>(entry.getValue())));
    }

    public void setModuleFilePatterns(Map<String, List<String>> moduleFilePatterns) {
        this.moduleFilePatterns = moduleFilePatterns;
    }

    @TaskAction
    public void writeListOfChangedModules() throws GitAPIException {

        final File fileToWrite = new File(getFileLocation());

        try(final FileWriter writer = new FileWriter(fileToWrite, false)){

            final GitRepository gitRepository = new GitRepository();
            final Optional<List<String>> pathsWithDiffOptional = gitRepository.getPathsThatHaveChanged(diffStrategy);

            final Map<String, Boolean> changedModules = new HashMap<>();

            //First ever deploy Write a list of all modules
            if(!pathsWithDiffOptional.isPresent()){
                addAllModulesChanged(getProject(), changedModules);
            }
            else{
                final List<String> pathsWithDiff = pathsWithDiffOptional.get();
                checkIfModuleHasChanged(getProject(), changedModules, pathsWithDiff, getFilePatterns(), getModuleFilePatterns(), false);
            }

            for(final Map.Entry<String, Boolean> entry:
                    changedModules.entrySet().stream()
                            .sorted(Comparator.comparing(Map.Entry::getKey)).collect(Collectors.toList())){
                logger.debug("Project: {}, {}", entry.getKey(),  entry.getValue());
                writer.write(entry.getKey() + "," + entry.getValue() + "\n");
            }

        }
        catch (IOException e){
            throw new RuntimeException("Couldn't open file for writing!"  + e.getMessage() +":" + Arrays.stream(e.getStackTrace()).map(StackTraceElement::toString).collect(joining("\n")));
        }
    }

    private void addAllModulesChanged(Project project, Map<String, Boolean> changedModules) {

        changedModules.put(project.getPath(), true);
        project.getSubprojects()
                .forEach(subProject -> addAllModulesChanged(subProject, changedModules));
    }

    private void checkIfModuleHasChanged(final Project project,
                                         final Map<String, Boolean> moduleMap,
                                         final List<String> pathWithDiff,
                                         final Set<String> defaultFilePatterns,
                                         final Map<String, Set<String>> moduleFilePatterns,
                                         final Boolean hasParentModuleChange){

        final GradleModule gradleModule = new GradleModule(project);

        final Boolean hasModuleChanged = hasParentModuleChange || gradleModule.hasChanged(moduleFilePatterns.getOrDefault(project.getPath(), defaultFilePatterns), pathWithDiff);

        if(hasModuleChanged){
            logger.info("Module {} has changed", project.getPath());
            moduleMap.putIfAbsent(project.getPath() , true);
        }
        else {
            logger.info("Module {} hasn't changed", project.getPath());
            moduleMap.putIfAbsent(project.getPath(), false);
        }

        project.getSubprojects()
                .forEach(subProject -> checkIfModuleHasChanged(subProject, moduleMap, pathWithDiff, defaultFilePatterns, moduleFilePatterns, hasModuleChanged));
    }

}
