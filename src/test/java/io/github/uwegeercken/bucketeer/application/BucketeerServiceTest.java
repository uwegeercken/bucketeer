package io.github.uwegeercken.bucketeer.application;

import io.github.uwegeercken.bucketeer.domain.port.out.S3StoragePort;
import io.github.uwegeercken.bucketeer.domain.template.PrefixTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BucketeerServiceTest {

    private S3StoragePort s3StoragePort;
    private BucketeerService service;

    @BeforeEach
    void setUp() {
        s3StoragePort = mock(S3StoragePort.class);
        service = new BucketeerService(s3StoragePort, null);
    }

    @Test
    @DisplayName("moveObject copies then deletes the source")
    void moveCopiesThenDeletes() {
        when(s3StoragePort.copyObject("server", "bucket", "a/old.txt", "bucket", "a/new.txt", false))
                .thenReturn(true);

        boolean moved = service.moveObject("server", "bucket", "a/old.txt", "a/new.txt");

        assertThat(moved).isTrue();
        verify(s3StoragePort).copyObject("server", "bucket", "a/old.txt", "bucket", "a/new.txt", false);
        verify(s3StoragePort).deleteObject("server", "bucket", "a/old.txt");
    }

    @Test
    @DisplayName("moveObject skips when the target exists and leaves the source untouched")
    void moveSkipsWhenTargetExists() {
        when(s3StoragePort.copyObject(anyString(), anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(false);

        boolean moved = service.moveObject("server", "bucket", "a/old.txt", "a/new.txt");

        assertThat(moved).isFalse();
        verify(s3StoragePort, never()).deleteObject(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("moveObject does not delete the source when the copy fails")
    void moveDoesNotDeleteOnCopyFailure() {
        when(s3StoragePort.copyObject(anyString(), anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenThrow(new RuntimeException("copy failed"));

        assertThatThrownBy(() -> service.moveObject("server", "bucket", "a/old.txt", "a/new.txt"))
                .hasMessageContaining("copy failed");
        verify(s3StoragePort, never()).deleteObject(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("moveObject reports a possible duplicate when the copy succeeded but the delete fails")
    void moveWarnsAboutDuplicateOnDeleteFailure() {
        when(s3StoragePort.copyObject(anyString(), anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(true);
        doThrow(new RuntimeException("Access Denied"))
                .when(s3StoragePort).deleteObject(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.moveObject("server", "bucket", "a/old.txt", "a/new.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Copy created at a/new.txt")
                .hasMessageContaining("deletion of a/old.txt failed")
                .hasMessageContaining("duplicate may exist");
        verify(s3StoragePort).copyObject("server", "bucket", "a/old.txt", "bucket", "a/new.txt", false);
        verify(s3StoragePort).deleteObject("server", "bucket", "a/old.txt");
    }

    @Test
    @DisplayName("moveObject rejects an empty target key")
    void moveRejectsEmptyTarget() {
        assertThatThrownBy(() -> service.moveObject("server", "bucket", "a/old.txt", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        verifyNoInteractions(s3StoragePort);
    }

    @Test
    @DisplayName("moveObject rejects a target equal to the source")
    void moveRejectsSameTarget() {
        assertThatThrownBy(() -> service.moveObject("server", "bucket", "a/old.txt", "a/old.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ");
        verifyNoInteractions(s3StoragePort);
    }

    @Test
    @DisplayName("deleteObject delegates to the storage port")
    void deleteDelegates() {
        service.deleteObject("server", "bucket", "a/file.txt");
        verify(s3StoragePort).deleteObject("server", "bucket", "a/file.txt");
    }
}
