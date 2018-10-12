package com.github.frankfarrell.blastradius;

import org.gradle.api.DomainObjectSet;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.UnknownConfigurationException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.util.*;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toList;

/**
 * Created by frankfarrell on 13/10/2017.
 */
public class GradleModule {

    private static final Logger logger = Logging.getLogger(GradleModule.class);

    private final Project project;

    public GradleModule(final Project project) {
        this.project = project;
    }

    //Recursively gets a projects dependencies including parent projects and root projects dependencies
    public Set<String> getProjectDependencies() {

        logger.info("Getting depedencies");

        final String rootPath =  project.getRootDir().getAbsolutePath();

        logger.info("Root path is {}", rootPath);

        if(project.getParent() == null){
            return Collections.emptySet();
        }
        else{
            final Configuration config;
            try{
                config = project.getConfigurations().getAt("runtime");
            }
            catch (UnknownConfigurationException e){
                //Eg if it is not a java project, since runtime is added by java configuration
                return Collections.emptySet();
            }

            final DomainObjectSet<ProjectDependency> projectDependencies = config.getAllDependencies().withType(ProjectDependency.class);
            final Iterator<ProjectDependency> iter = projectDependencies.iterator();

            final List<ProjectDependency> projectDependencyList = new ArrayList<>();
            final Set<String> projectDependencyPaths = new HashSet<>();

            //To see if there are any changes in the project itself
            projectDependencyPaths.add(project.getProjectDir().getAbsolutePath().replace("^:" ,"/").replace(rootPath ,""));

            while (iter.hasNext()){
                final ProjectDependency next = iter.next();
                projectDependencyList.add(next);
                final String projectPath = next.getDependencyProject().getProjectDir().getAbsolutePath();

                logger.info("Path of dependency {}", projectPath);
                projectDependencyPaths.add(projectPath.replace("^:" ,"/").replace(rootPath ,""));
            }

            for(ProjectDependency projectDependency: projectDependencyList){
                final GradleModule dependencyModule = new GradleModule(projectDependency.getDependencyProject());
                projectDependencyPaths.addAll(dependencyModule.getProjectDependencies());
            }

            return projectDependencyPaths;
        }
    }

    public Boolean hasChanged(Set<String> filePatterns, final List<String> pathsWithDiff){

        final List<Pattern> candidatePatterns =
                filePatterns.stream()
                        .map(pattern ->
                                Pattern.compile(
                                        (project.getPath()
                                                .replace("\\", "/")
                                                .replace(":", "/")
                                                + pattern)
                                                //This is for the root patterns
                                                .replace("//", "/")))
                        .collect(toList());

        final List<Pattern> candidatePatternsForDependencies = getProjectDependencies().stream()
                .map(projectDependency ->
                        filePatterns
                                .stream()
                                .map(pattern ->
                                        //windows
                                        Pattern.compile(
                                                (projectDependency.replace("\\", "/")
                                                        + pattern)
                                                        //This is for the root patterns
                                                        .replace("//", "/"))
                                )
                                .collect(toList())
                )
                .flatMap(List::stream)
                .collect(toList());

        candidatePatterns.addAll(candidatePatternsForDependencies);
        candidatePatterns.forEach(patt -> logger.info("Candidate pattern {}", patt.pattern()));

        final boolean anyChange = pathsWithDiff.
                stream().
                anyMatch(pathWithDiff ->
                        candidatePatterns.stream()
                                .anyMatch(patt -> {
                                    String path = pathWithDiff.replace("\\", "/");
                                    boolean matches = patt.matcher(path).matches();
                                    logger.debug("Testing {} against pattern {} : Result is {}", patt.pattern(), matches);
                                    return matches;
                                })
                );

        logger.info("Is there any change?  {}", anyChange);

        return anyChange;
    }
}
