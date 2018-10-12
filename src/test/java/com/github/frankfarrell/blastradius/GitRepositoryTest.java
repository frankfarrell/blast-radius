package com.github.frankfarrell.blastradius;

import com.github.zafarkhaja.semver.Version;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Created by frankfarrell on 09/03/2018.
 */
public class GitRepositoryTest {

    @Mock
    Repository mockRepository;

    GitRepository gitRepositoryUnderTest;

    @Before
    public void setup(){
        MockitoAnnotations.initMocks(this);
        gitRepositoryUnderTest = new GitRepository(mockRepository);
    }

    @Test
    public void itReturnsASortedListOfTagsThatAreVersions(){

        Map<String, Ref> mockTags = new HashMap<>();
        mockTags.put("1.0.0", mock(Ref.class));
        mockTags.put("1.0.1", mock(Ref.class));
        mockTags.put("0.0.1", mock(Ref.class));
        mockTags.put("nonsense", mock(Ref.class));

        when(mockRepository.getTags()).thenReturn(mockTags);

        assertThat(gitRepositoryUnderTest.getAllVersionsInRepository()).containsExactly(Version.valueOf("0.0.1"), Version.valueOf("1.0.0"),  Version.valueOf("1.0.1"));
    }

    @Test
    public void itGetsTagsOnHead() throws IOException {

        ObjectId mockHeadId = ObjectId.fromString("83baae61804e65cc73a7201a7252750c76066a30");
        Ref mockHeadRef = mock(Ref.class);
        when(mockHeadRef.getPeeledObjectId()).thenReturn(mockHeadId);

        Ref mockNormalRef = mock(Ref.class);
        when(mockNormalRef.getPeeledObjectId()).thenReturn(ObjectId.fromString("83baae61804e65cc73a7201a7252750c76066a31"));

        Map<String, Ref> mockTags = new HashMap<>();
        mockTags.put("1.0.0", mockHeadRef);
        mockTags.put("1.0.1", mockNormalRef);
        mockTags.put("nonsense", mockHeadRef);

        when(mockRepository.getTags()).thenReturn(mockTags);
        when(mockRepository.resolve(anyString())).thenReturn(mockHeadId);

        assertThat(gitRepositoryUnderTest.getTagsOnHead()).containsExactlyInAnyOrder("1.0.0", "nonsense");
    }

    @Test
    public void itReturnsCachedHeadVersion() throws IOException {
        gitRepositoryUnderTest.headVersion = Optional.of(Version.valueOf("1.0.1"));

        assertThat(gitRepositoryUnderTest.getHeadVersion()).contains(Version.valueOf("1.0.1"));
    }
}
