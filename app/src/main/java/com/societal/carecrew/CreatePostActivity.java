// CreatePostActivity.java
package com.societal.carecrew;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.societal.carecrew.databinding.ActivityCreatePostBinding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class CreatePostActivity extends AppCompatActivity {

    private ActivityCreatePostBinding binding;
    private Uri imageUri;
    private ProgressDialog progressDialog;
    private FirebaseAuth mAuth;
    private FirebaseUser currentUser;
    private DatabaseReference postsRef;
    private static final String IMG_BB_API_KEY = "fe3ec739386bdf08dec5cae269f7cb10"; // Replace with your actual API key
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<Intent> imagePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCreatePostBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        currentUser = mAuth.getCurrentUser();
        postsRef = FirebaseDatabase.getInstance().getReference("posts");

        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Uploading image...");

        requestPermissionLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                    if (isGranted) {
                        openImageChooser();
                    } else {
                        Toast.makeText(this, "Permission denied to read images", Toast.LENGTH_SHORT).show();
                    }
                });

        imagePickerLauncher =
                registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                                Intent data = result.getData();
                                if (data != null && data.getData() != null) {
                                    imageUri = data.getData();
                                    binding.postImageView.setImageURI(imageUri);
                                }
                            }
                        });

        binding.selectImageButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.READ_MEDIA_IMAGES);
            } else {
                openImageChooser();
            }
        });

        binding.postButton.setOnClickListener(v -> {
            if (imageUri != null && !binding.captionEditText.getText().toString().isEmpty()) {
                progressDialog.show();
                uploadImageToImgBB();
            } else {
                Toast.makeText(this, "Please select an image and write a caption", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openImageChooser() {
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        imagePickerLauncher.launch(intent);
    }

    private void uploadImageToImgBB() {
        Bitmap bitmap = ((BitmapDrawable) binding.postImageView.getDrawable()).getBitmap();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
        byte[] byteArray = stream.toByteArray();

        OkHttpClient client = new OkHttpClient().newBuilder().build();
        RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("key", IMG_BB_API_KEY)
                .addFormDataPart("image", "image.jpg",
                        RequestBody.create(byteArray, MediaType.parse("image/jpeg")))
                .build();

        Request request = new Request.Builder()
                .url("https://api.imgbb.com/1/upload")
                .method("POST", requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(CreatePostActivity.this, "Image upload failed", Toast.LENGTH_SHORT).show();
                    Log.e("CreatePostActivity", "ImgBB upload failed: " + e.getMessage());
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String responseBody = response.body().string();
                        String imageUrl = parseImageUrlFromJsonResponse(responseBody);
                        if (imageUrl != null) {
                            runOnUiThread(() -> createPost(imageUrl));
                        } else {
                            runOnUiThread(() -> {
                                progressDialog.dismiss();
                                Toast.makeText(CreatePostActivity.this, "Failed to get image URL", Toast.LENGTH_SHORT).show();
                            });
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            progressDialog.dismiss();
                            Toast.makeText(CreatePostActivity.this, "Error parsing response", Toast.LENGTH_SHORT).show();
                            Log.e("CreatePostActivity", "Error parsing ImgBB response: " + e.getMessage());
                        });
                    }
                } else {
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        Toast.makeText(CreatePostActivity.this, "Image upload failed", Toast.LENGTH_SHORT).show();
                        Log.e("CreatePostActivity", "ImgBB upload failed: " + response.code());
                    });
                }
            }
        });
    }

    private String parseImageUrlFromJsonResponse(String responseBody) {
        try {
            org.json.JSONObject jsonObject = new org.json.JSONObject(responseBody);
            org.json.JSONObject dataObject = jsonObject.getJSONObject("data");
            return dataObject.getString("url");
        } catch (org.json.JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void createPost(String imageUrl) {
        String caption = Objects.requireNonNull(binding.captionEditText.getText()).toString();
        String username = currentUser.getDisplayName(); // Or fetch from database
        Post newPost = new Post(currentUser.getUid(), username, caption, imageUrl);
        postsRef.push().setValue(newPost)
                .addOnSuccessListener(unused -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "Post created successfully", Toast.LENGTH_SHORT).show();
                    finish(); // Finish the activity after successful post creation
                })
                .addOnFailureListener(e -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "Failed to create post", Toast.LENGTH_SHORT).show();
                });
    }
}