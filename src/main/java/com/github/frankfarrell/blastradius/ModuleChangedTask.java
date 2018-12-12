package com.github.frankfarrell.blastradius;

import com.github.frankfarrell.blastradius.DiffStrategy;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.gradle.api.Project;
import org.gradle.api.internal.ConventionTask;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by frankfarrell on 11/09/2017.
 */
public class ModuleChangedTask extends ConventionTask {

    private static Logger logger = Logging.getLogger(ModuleChangedTask.class);

    public boolean getToDeploy() {
        return toDeploy;
    }

    private boolean toDeploy;

    private DiffStrategy diffStrategy = DiffStrategy.JENKINS_LAST_COMMIT;

    private String previousCommit;

    public static final Set<String> DEFAULT_FILE_PATTERNS =  Collections.unmodifiableSet(Stream.of("/[^.]*.gradle", "/src/main/.*", "/deploy/.*").collect(Collectors.toSet()));

    private List<String> filePatterns;

    public List<String> getFilePatterns() {
        return filePatterns;
    }

    public void setFilePatterns(final List<String> filePatterns) {
        this.filePatterns = filePatterns;
    }

    public void setDiffStrategy(final String diffStrategy) {
        this.diffStrategy = DiffStrategy.valueOf(diffStrategy);
    }

    public String getPreviousCommit() {
        return previousCommit;
    }

    public void setPreviousCommit(String previousCommit) {
        this.previousCommit = previousCommit;
    }

    @TaskAction
    public void shouldModuleBeDeployedTask() throws IOException, GitAPIException {

        //Sensible defaults
        if(filePatterns == null){
            toDeploy = shouldModuleBeDeployed(getProject(), DEFAULT_FILE_PATTERNS);
        }
        else{
            toDeploy = shouldModuleBeDeployed(getProject(), new HashSet<>(filePatterns));
        }
    }

    public boolean shouldModuleBeDeployed(final Project project, Set<String> filePatterns) throws IOException, GitAPIException {
        return shouldModuleBeDeployed(project, new GradleModule(project), filePatterns);
    }

    /*
    Returns true if it should be deployed
    Will also return true if this is the first ever commit
     */
    public boolean shouldModuleBeDeployed(final Project project, final GradleModule gradleModule, Set<String> filePatterns) throws IOException, GitAPIException {

        final GitRepository gitRepository = new GitRepository();
        final Optional<List<String>> pathsWithDiffOptional = gitRepository.getPathsThatHaveChanged(diffStrategy, Optional.ofNullable(previousCommit));

        return pathsWithDiffOptional.map(pathsWithDiff -> gradleModule.hasChanged(filePatterns, pathsWithDiff)).orElse(true);
    }
}
