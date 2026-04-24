package com.example.couplecredit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class AvatarPersistenceSourceTest {

    @Test
    public void avatarUploadApi_doesNotTreatProfileUpdateFailureAsSuccess() throws Exception {
        String source = new String(
                Files.readAllBytes(Paths.get("src/main/java/com/example/couplecredit/api/AvatarUploadApi.java")),
                StandardCharsets.UTF_8
        );

        int errorBranch = source.indexOf("Avatar uploaded but profile update failed");
        int successCallback = source.indexOf("callback.onUploadSuccess(imageUrl);", errorBranch);
        int errorCallback = source.indexOf("callback.onUploadError(", errorBranch);

        assertTrue(errorBranch >= 0);
        assertTrue(errorCallback > errorBranch);
        assertFalse(successCallback > errorBranch && successCallback < errorCallback + 120);
    }

    @Test
    public void myFragment_doesNotShowLocalAvatarBeforeUploadPersists() throws Exception {
        String source = new String(
                Files.readAllBytes(Paths.get("src/main/java/com/example/couplecredit/fragment/MyFragment.java")),
                StandardCharsets.UTF_8
        );

        assertFalse(source.contains("uploadAvatarToServer(selectedImageUri);\n                setUserAvatar(selectedImageUri);"));
    }

    @Test
    public void userSettingsActivity_doesNotShowLocalAvatarBeforeUploadPersists() throws Exception {
        String source = new String(
                Files.readAllBytes(Paths.get("src/main/java/com/example/couplecredit/activity/UserSettingsActivity.java")),
                StandardCharsets.UTF_8
        );

        assertFalse(source.contains("uploadAvatarToServer(selectedImageUri);\n                setUserAvatar(selectedImageUri);"));
    }
}
