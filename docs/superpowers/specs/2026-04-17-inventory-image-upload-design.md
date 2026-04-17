# Inventory Image Upload Design

## Summary

Enable users to attach photos to inventory items via camera capture or gallery selection. Photos are uploaded to the server and visible to both partners. The server upload infrastructure already exists — this spec connects the Android-side pipeline end-to-end.

## Current State

- Server: `POST /api/upload/image` (multer, 5MB limit, saves to `uploads/`) — fully working
- Server: `GET /uploads/:filename` — static file serving — fully working
- Android: `AuthApiClient.uploadImage(InputStream, fileName, ImageUploadCallback)` — fully implemented
- Android: `InventoryFragment` has gallery picker UI but does NOT call upload — sends local `content://` URI to server instead
- Database: `inventory.image_url VARCHAR(500)` — already supports URLs

## Design

### 1. Android Camera Integration

**Permissions (AndroidManifest.xml)**:
- Add `android.permission.CAMERA`
- Add `FileProvider` declaration with `@xml/file_paths` config

**FileProvider config** (`res/xml/file_paths.xml`):
- External files directory for camera temp photos

**Image source selection**:
- Clicking the image area in add/edit dialog shows a BottomSheet or AlertDialog with two options: "拍照" / "从图库选择"
- "拍照": check camera permission → launch `MediaStore.ACTION_IMAGE_CAPTURE` with FileProvider URI → on result, load into preview
- "从图库选择": existing `ACTION_PICK` logic

**Permission handling**:
- Camera: runtime request for `CAMERA` permission
- Gallery: existing `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` handling

### 2. Upload Pipeline

**Save flow when image is selected**:
1. User selects image (camera or gallery) → stored as `pendingImageUrl` (local URI)
2. User clicks save → if `pendingImageUrl` is set:
   a. Open InputStream from local URI via `ContentResolver`
   b. Call `AuthApiClient.uploadImage()` to upload to server
   c. Receive server URL (e.g., `/uploads/1234567-photo.jpg`)
   d. Use server URL as `imageUrl` in create/update request
3. If editing and image unchanged → keep existing `imageUrl`
4. If no image selected → `imageUrl` is null

**Loading state**: disable save button and show progress during upload to prevent duplicate submissions.

### 3. Image Display

**Inventory list**:
- Each item shows a thumbnail loaded via Glide from `imageUrl` (server URL)
- No image → show default placeholder icon
- Partners see the same server URL, so both can view the image

**Detail/Edit dialog**:
- Show full preview of current image when editing existing item with image

### 4. Server Changes

None required. Existing upload endpoint and static serving handle everything.

### 5. Error Handling

- Camera permission denied → show toast, continue without photo
- Upload failure → show error toast, don't save the item (let user retry)
- Network timeout → upload has 15s timeout already, show timeout message

## Scope

- Single image per inventory item (existing `image_url` field)
- No new server endpoints or database schema changes
- No AI image generation (existing stub remains untouched)
