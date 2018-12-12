package com.github.frankfarrell.blastradius;

import com.github.zafarkhaja.semver.Version;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Created by frankfarrell on 13/10/2017
 *
 */
public class GitRepository {

    private static final Logger logger = Logging.getLogger(GitRepository.class);

    private final Repository repository;

    //Used for lazy evaluation
    protected Optional<Version> headVersion;

    public GitRepository() throws IOException {
        final FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
        repositoryBuilder.findGitDir();
        this.repository = repositoryBuilder.build();
    }

    public GitRepository(final Repository repository){
        this.repository = repository;
    }

    /*
    An Optional.empty() result means that changes cannot be determined.
    The client can decide what to do, but its recommened to deploy everything
     */

    public Optional<List<String>> getPathsThatHaveChanged(final DiffStrategy diffStrategy) throws IOException, GitAPIException {
        return getPathsThatHaveChanged(diffStrategy, Optional.empty());
    }

    public Optional<List<String>> getPathsThatHaveChanged(final DiffStrategy diffStrategy, final Optional<String> previousCommit) throws IOException, GitAPIException {

        logger.info("Currently on branch {}", repository.getBranch());

        final Optional<CommitIds> commitIds;

        //TODO Consider if we want fallbacks here or not -ffarrell
        switch (diffStrategy){
            case JENKINS_LAST_COMMIT:
                commitIds = getCommitIdsFromJenkinsEnvVar();
                break;
            case PREVIOUS_TAG:
                commitIds = getCommitIdsFromPreviousTag();
                break;

            case PREVIOUS_COMMIT:
                commitIds = getCommitIdsFromPreviousCommit();
                break;
            case SPECIFIC_COMMIT:
                if(previousCommit.isPresent()){
                    commitIds = Optional.of(new CommitIds(repository.resolve(previousCommit.get()) , repository.resolve(Constants.HEAD)));
                }
                else{
                    throw new InvalidUserDataException("previousCommit hash must be specified if the SPECIFIC_COMMIT diff strategy is used");
                }
                break;
            default:
                throw new RuntimeException("This is impossible, but it makes the compiler happy");
        }

        if(commitIds.isPresent()){
            logger.info("Prev commit id: {}", commitIds.get().previousCommit);
            logger.info("Current commit id: {}", commitIds.get().currentCommit);

            try{
                final List<String> pathsWithDiff = getPathsWithDiff(repository,
                        commitIds.get().previousCommit,
                        commitIds.get().currentCommit);
                return Optional.of(pathsWithDiff);
            }
            //If something goes wrong here it probably means that the git ObjectIds are messed up. We'll just deploy everything
            catch (Exception e){
                return Optional.empty();
            }
        }
        else {
            return Optional.empty();
        }
    }

    //its just a normal commit. Compare HEAD with HEAD ~1
    private Optional<CommitIds> getCommitIdsFromPreviousCommit() throws IOException {

        logger.info("Comparing to previous head");

        final String previousCommitPointer = "HEAD~1";

        //Need to confirm that it returns nulls if it doesnt exist
        final Optional<ObjectId> previousCommitId = Optional.ofNullable(repository.resolve(previousCommitPointer));

        if(previousCommitId.isPresent()){
            return Optional.of(new CommitIds(previousCommitId.get(), repository.resolve(Constants.HEAD)));
        }
        else{
            //First ever commit
            return Optional.empty();
        }
    }

    private Optional<CommitIds> getCommitIdsFromPreviousTag() throws IOException {

        final List<Version> versions = getAllVersionsInRepository();
        final Map<String, Ref> allTagsOnRepository = repository.getTags();

        if(getHeadVersion().isPresent()){
            System.out.println("Using head version");

            final Version headVer = getHeadVersion().get();

            logger.debug("Current version: {}", headVer);


            final int indexOfHead = versions.indexOf(headVer);

            if (indexOfHead > 0) {
                final Version currVersion = versions.get(indexOfHead);
                final Version prevVersion = versions.get(indexOfHead - 1);
                logger.info("Prev version: {}", prevVersion);

                return Optional.of(new CommitIds(allTagsOnRepository.get(prevVersion.toString()).getObjectId(), allTagsOnRepository.get(currVersion.toString()).getObjectId()));
            }
            //First ever tagged commit, must deploy
            else{
                return Optional.empty();
            }
        }
        else {
            //HEAD is not a tagged commit
            if(versions.size()>0){
                final Version prevVersion = versions.get(versions.size() - 1);
                logger.info("Prev version: {}", prevVersion);

                return Optional.of(new CommitIds(allTagsOnRepository.get(prevVersion.toString()).getObjectId(), repository.resolve(Constants.HEAD)));
            }
            else{
                return Optional.empty();
            }
        }

    }

    //Running on jenkins with a previous commit
    //Using https://wiki.jenkins.io/display/JENKINS/Git+Plugin
    private Optional<CommitIds> getCommitIdsFromJenkinsEnvVar() throws IOException {

        final Optional<String> jenkinsPreviousSuccessfulCommit = Optional.ofNullable(System.getenv("GIT_PREVIOUS_SUCCESSFUL_COMMIT"));

        if(jenkinsPreviousSuccessfulCommit.isPresent()){

            final Optional<ObjectId> previousCommitId = Optional.ofNullable(repository.resolve(jenkinsPreviousSuccessfulCommit.get()));

            if(previousCommitId.isPresent()){
                return Optional.of(new CommitIds(previousCommitId.get(), repository.resolve(Constants.HEAD)));
            }
            else{
                //This could happen if the previos ref in jenkinsPreviousSuccessfulCommit doesn't exist, eg after a rebase
                return Optional.empty();
            }
        }
        else{
            return Optional.empty();
        }
    }

    protected List<Version> getAllVersionsInRepository() {
        return repository
                .getTags()
                .keySet()
                .stream()
                .map(key -> {
                    try {
                        return Optional.of(Version.valueOf(key));
                    } catch (Exception e) {
                        return Optional.<Version>empty();
                    }
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .sorted()
                .collect(Collectors.toList());
    }

    //Lazy evaluation
    protected synchronized Optional<Version> getHeadVersion() throws IOException {

        if(headVersion != null){
            return headVersion;
        }
        else {

            final List<String> tagsOnHead = getTagsOnHead();

            logger.debug("Tags on head {}", tagsOnHead.size());

            tagsOnHead.forEach(tag -> logger.debug("Tag on head {}", tag));

            this.headVersion =
                    tagsOnHead.stream().filter(tag -> {
                        try {
                            logger.debug("Trying to turn tag into version for {}", tag);
                            Version.valueOf(tag);
                            return true;
                        } catch (Exception e) {
                            logger.error("Failed to turn tag into version for {}", tag ,e);
                            return false;
                        }
                    })
                    .map(Version::valueOf)
                    .findFirst();
            return headVersion;
        }
    }

    protected List<String> getTagsOnHead() throws IOException {

        final ObjectId head = repository.resolve(Constants.HEAD);

        logger.info("Head is {}", head.toString());

        return repository.getTags().entrySet().stream()
                .filter(entry -> {
                    final Boolean value = entry.getValue().getPeeledObjectId() != null &&
                            entry.getValue().getPeeledObjectId().compareTo(head) == 0;
                    logger.debug("Comparing {} to {} : result {}", entry.getValue().getPeeledObjectId(), head, value);
                    return value;

                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    protected static List<String> getPathsWithDiff(Repository repository, ObjectId previousCommit, ObjectId currentCommit) throws GitAPIException, IOException {
        Git git = Git.wrap(repository);

        try (ObjectReader reader = repository.newObjectReader()) {

            final RevWalk walk = new RevWalk(repository);

            final RevCommit prevRevCommit = walk.parseCommit(previousCommit);
            final ObjectId prevTreeId = prevRevCommit.getTree().getId();

            final CanonicalTreeParser previousVersionTreeIter = new CanonicalTreeParser(null, reader, prevTreeId);

            final RevCommit currRevCommit = walk.parseCommit(currentCommit);
            final ObjectId currTreeId = currRevCommit.getTree().getId();

            final CanonicalTreeParser currVersionTreeIter = new CanonicalTreeParser(null, reader, currTreeId);

            logger.info("Trying git diff {} with {}", prevRevCommit.toString(), currRevCommit.toString()) ;
            final List<DiffEntry> diff = git.diff()
                    .setNewTree(currVersionTreeIter)
                    .setOldTree(previousVersionTreeIter)
                    .setShowNameAndStatusOnly(true)
                    .call();

            return diff.stream()
                    /*
                    Would be ideal if we could do this, but rename modifications could also include changes
                    .filter(diffe -> diffe.getChangeType().equals(DiffEntry.ChangeType.MODIFY))
                     */
                    .map(x -> "/" + x.getNewPath())
                    .peek(diffe -> logger.debug("Diff {}", diffe))
                    .collect(Collectors.toList());

        } catch (IncorrectObjectTypeException e) {
            //TODO What does this actually mean? -ffarrell 2017-09-11
            throw e;
        }
    }

    private class CommitIds {
        final ObjectId previousCommit;
        final ObjectId currentCommit;

        private CommitIds(final ObjectId previousCommit, final ObjectId currentCommit) {
            this.previousCommit = previousCommit;
            this.currentCommit = currentCommit;
        }
    }

}
