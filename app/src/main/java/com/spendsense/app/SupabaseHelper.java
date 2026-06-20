package com.spendsense.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.*;

public class SupabaseHelper {
    private static final String TAG = "SupabaseHelper";
    private static final String URL = "https://ftwczeglepntedkwvulp.supabase.co";
    private static final String ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImZ0d2N6ZWdsZXBudGVka3d2dWxwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzUyNTU0ODYsImV4cCI6MjA5MDgzMTQ4Nn0.kHpQ1P7oVhJWiuMHgw75ms_u4DLAKHylCXD8HIUInEA";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new Gson();
    private final SharedPreferences prefs;

    public SupabaseHelper(Context context) {
        prefs = context.getSharedPreferences("trackmyspend", Context.MODE_PRIVATE);
    }

    public String getAccessToken() {
        return prefs.getString("access_token", null);
    }

    public String getUserId() {
        return prefs.getString("user_id", null);
    }

    public boolean isLoggedIn() {
        String token = getAccessToken();
        String userId = getUserId();
        return token != null && !token.isEmpty() && userId != null && !userId.isEmpty();
    }

    // ── Token Refresh ──
    public interface RefreshCallback {
        void onSuccess();
        void onError(String message);
    }

    public void refreshSession(RefreshCallback callback) {
        String refreshToken = prefs.getString("refresh_token", null);
        if (refreshToken == null || refreshToken.isEmpty()) {
            if (callback != null) callback.onError("No refresh token available");
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("refresh_token", refreshToken);

        Request request = new Request.Builder()
                .url(URL + "/auth/v1/token?grant_type=refresh_token")
                .addHeader("apikey", ANON_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (callback != null) callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                String responseBody = safeReadBody(response);
                try {
                    if (response.isSuccessful()) {
                        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
                        String newToken = safeGetString(json, "access_token", null);
                        String newRefreshToken = safeGetString(json, "refresh_token", null);

                        if (newToken != null && !newToken.isEmpty()) {
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putString("access_token", newToken);
                            if (newRefreshToken != null && !newRefreshToken.isEmpty()) {
                                editor.putString("refresh_token", newRefreshToken);
                            }
                            editor.apply();

                            if (callback != null) callback.onSuccess();
                        } else {
                            if (callback != null) callback.onError("Invalid token response");
                        }
                    } else {
                        signOut();
                        if (callback != null) callback.onError("Session expired, please login again");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Refresh parse error", e);
                    if (callback != null) callback.onError("Unexpected error during token refresh");
                }
            }
        });
    }

    // ── Safe body reader ──
    private String safeReadBody(Response response) {
        try {
            ResponseBody body = response.body();
            if (body != null) {
                return body.string();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read response body", e);
        }
        return "";
    }

    // ── Safe JSON string getter ──
    private String safeGetString(JsonObject obj, String key, String fallback) {
        if (obj != null && obj.has(key)) {
            JsonElement el = obj.get(key);
            if (el != null && !el.isJsonNull()) {
                return el.getAsString();
            }
        }
        return fallback;
    }

    private double safeGetDouble(JsonObject obj, String key, double fallback) {
        if (obj != null && obj.has(key)) {
            JsonElement el = obj.get(key);
            if (el != null && !el.isJsonNull()) {
                return el.getAsDouble();
            }
        }
        return fallback;
    }

    // ── AUTH CALLBACKS ──
    public interface AuthCallback {
        void onSuccess(String userId, String accessToken);
        void onError(String message);
    }

    public interface SignUpCallback {
        void onSuccess(String userId, String accessToken);
        void onConfirmationRequired(String email);
        void onError(String message);
    }

    // ── AUTH: Sign Up ──
    public void signUp(String email, String password, String fullName, SignUpCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);
        JsonObject data = new JsonObject();
        data.addProperty("full_name", fullName);
        JsonObject options = new JsonObject();
        options.add("data", data);
        body.add("options", options);

        Request request = new Request.Builder()
                .url(URL + "/auth/v1/signup")
                .addHeader("apikey", ANON_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                String responseBody = safeReadBody(response);
                try {
                    if (response.isSuccessful()) {
                        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
                        String token = safeGetString(json, "access_token", null);

                        if (token == null || token.isEmpty()) {
                            callback.onConfirmationRequired(email);
                        } else {
                            JsonObject user = json.getAsJsonObject("user");
                            String uid = safeGetString(user, "id", "");
                            saveSession(uid, token);
                            callback.onSuccess(uid, token);
                        }
                    } else {
                        String msg = parseErrorMessage(responseBody, "Sign up failed");
                        callback.onError(msg);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "SignUp parse error", e);
                    callback.onError("Unexpected error during sign up");
                }
            }
        });
    }

    // ── AUTH: Sign In ──
    public void signIn(String email, String password, AuthCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);

        Request request = new Request.Builder()
                .url(URL + "/auth/v1/token?grant_type=password")
                .addHeader("apikey", ANON_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                String responseBody = safeReadBody(response);
                try {
                    if (response.isSuccessful()) {
                        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
                        String token = safeGetString(json, "access_token", "");
                        String refreshToken = safeGetString(json, "refresh_token", "");
                        JsonObject user = json.getAsJsonObject("user");
                        String uid = user != null ? safeGetString(user, "id", "") : "";
                        saveSession(uid, token, refreshToken);
                        callback.onSuccess(uid, token);
                    } else {
                        String msg = parseErrorMessage(responseBody, "Login failed");
                        callback.onError(msg);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "SignIn parse error", e);
                    callback.onError("Unexpected error during login");
                }
            }
        });
    }

    // ── AUTH: Password Reset ──
    public void resetPassword(String email, AuthCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);

        Request request = new Request.Builder()
                .url(URL + "/auth/v1/recover")
                .addHeader("apikey", ANON_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (response.isSuccessful()) {
                    callback.onSuccess(null, null);
                } else {
                    String responseBody = safeReadBody(response);
                    String msg = parseErrorMessage(responseBody, "Failed to send reset email");
                    callback.onError(msg);
                }
            }
        });
    }

    // ── AUTH: Resend Verification Email ──
    public void resendVerification(String email, SimpleCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("type", "signup");

        Request request = new Request.Builder()
                .url(URL + "/auth/v1/resend")
                .addHeader("apikey", ANON_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (response.isSuccessful()) {
                    callback.onSuccess();
                } else {
                    String responseBody = safeReadBody(response);
                    String msg = parseErrorMessage(responseBody, "Failed to resend verification");
                    callback.onError(msg);
                }
            }
        });
    }

    public void signOut() {
        prefs.edit().clear().apply();
    }

    private void saveSession(String userId, String accessToken, String refreshToken) {
        prefs.edit()
                .putString("user_id", userId)
                .putString("access_token", accessToken)
                .putString("refresh_token", refreshToken != null ? refreshToken : "")
                .apply();
    }

    private void saveSession(String userId, String accessToken) {
        saveSession(userId, accessToken, null);
    }

    // ── TRANSACTIONS: Insert ──
    public interface SimpleCallback {
        void onSuccess();
        void onError(String message);
    }

    public void insertTransaction(String type, double amount, String category,
                                  String description, String source, String smsRaw,
                                  String transactionDate, SimpleCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("user_id", getUserId());
        body.addProperty("type", type);
        body.addProperty("amount", amount);
        body.addProperty("category", category);
        body.addProperty("description", description);
        body.addProperty("source", source);
        if (smsRaw != null) body.addProperty("sms_raw", smsRaw);
        if (transactionDate != null && !transactionDate.isEmpty()) {
            body.addProperty("transaction_date", transactionDate);
        }

        Request request = new Request.Builder()
                .url(URL + "/rest/v1/transactions")
                .addHeader("apikey", ANON_KEY)
                .addHeader("Authorization", "Bearer " + getAccessToken())
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (callback != null) callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (response.isSuccessful()) {
                    if (callback != null) callback.onSuccess();
                } else {
                    String err = safeReadBody(response);
                    Log.e(TAG, "Insert error: " + err);
                    if (callback != null) callback.onError(err);
                }
            }
        });
    }

    public void insertTransaction(String type, double amount, String category,
                                  String description, String source, String smsRaw,
                                  SimpleCallback callback) {
        insertTransaction(type, amount, category, description, source, smsRaw, null, callback);
    }

    // ── TRANSACTIONS: Fetch ──
    public interface TransactionsCallback {
        void onSuccess(List<Transaction> transactions);
        void onError(String message);
    }

    public void fetchTransactions(TransactionsCallback callback) {
        String userId = getUserId();
        String token = getAccessToken();
        if (userId == null || token == null) {
            callback.onError("Not logged in");
            return;
        }

        Request request = new Request.Builder()
                .url(URL + "/rest/v1/transactions?user_id=eq." + userId
                        + "&order=transaction_date.desc&limit=200")
                .addHeader("apikey", ANON_KEY)
                .addHeader("Authorization", "Bearer " + token)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                String body = safeReadBody(response);
                try {
                    if (response.isSuccessful()) {
                        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
                        List<Transaction> list = new ArrayList<>();
                        for (int i = 0; i < arr.size(); i++) {
                            list.add(Transaction.fromJson(arr.get(i).getAsJsonObject()));
                        }
                        callback.onSuccess(list);
                    } else {
                        callback.onError(body);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Fetch parse error", e);
                    callback.onError("Error parsing transactions");
                }
            }
        });
    }

    // ── TRANSACTIONS: Update ──
    public void updateTransaction(String id, String type, double amount, String category,
                                  String description, String transactionDate, SimpleCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        body.addProperty("amount", amount);
        body.addProperty("category", category);
        body.addProperty("description", description);
        body.addProperty("transaction_date", transactionDate);

        Request request = new Request.Builder()
                .url(URL + "/rest/v1/transactions?id=eq." + id)
                .addHeader("apikey", ANON_KEY)
                .addHeader("Authorization", "Bearer " + getAccessToken())
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .patch(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (callback != null) callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (response.isSuccessful()) {
                    if (callback != null) callback.onSuccess();
                } else {
                    if (callback != null) callback.onError(safeReadBody(response));
                }
            }
        });
    }

    // ── TRANSACTIONS: Delete ──
    public void deleteTransaction(String id, SimpleCallback callback) {
        Request request = new Request.Builder()
                .url(URL + "/rest/v1/transactions?id=eq." + id)
                .addHeader("apikey", ANON_KEY)
                .addHeader("Authorization", "Bearer " + getAccessToken())
                .delete()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (callback != null) callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (response.isSuccessful()) {
                    if (callback != null) callback.onSuccess();
                } else {
                    if (callback != null) callback.onError(safeReadBody(response));
                }
            }
        });
    }

    // ── PROFILES ──
    public interface ProfileCallback {
        void onSuccess(String fullName, double monthlyBudget);
        void onError(String message);
    }

    public void getProfile(ProfileCallback callback) {
        String userId = getUserId();
        String token = getAccessToken();
        if (userId == null || token == null) {
            callback.onError("Not logged in");
            return;
        }

        Request request = new Request.Builder()
                .url(URL + "/rest/v1/profiles?id=eq." + userId)
                .addHeader("apikey", ANON_KEY)
                .addHeader("Authorization", "Bearer " + token)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                String body = safeReadBody(response);
                try {
                    if (response.isSuccessful()) {
                        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
                        if (arr.size() > 0) {
                            JsonObject obj = arr.get(0).getAsJsonObject();
                            String name = safeGetString(obj, "full_name", "");
                            double budget = safeGetDouble(obj, "monthly_budget", 0);
                            callback.onSuccess(name, budget);
                        } else {
                            callback.onSuccess("", 0);
                        }
                    } else {
                        callback.onError(body);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Profile parse error", e);
                    callback.onError("Error loading profile");
                }
            }
        });
    }

    /**
     * Creates or updates the current user's profile row.
     *
     * NOTE: This uses an UPSERT (POST + Prefer: resolution=merge-duplicates)
     * instead of a plain PATCH. A PATCH to a row that doesn't exist yet
     * returns a *successful* empty response from PostgREST -- it looks like
     * the save worked, but nothing was actually written. This previously
     * caused profile data (name, budget) to silently fail to persist for
     * any account whose `profiles` row was missing (e.g. created before
     * the on_auth_user_created trigger existed), making it seem like data
     * "disappeared" until logout/login.
     */
    public void updateProfile(String fullName, double monthlyBudget, SimpleCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("id", getUserId());
        body.addProperty("full_name", fullName);
        body.addProperty("monthly_budget", monthlyBudget);

        Request request = new Request.Builder()
                .url(URL + "/rest/v1/profiles")
                .addHeader("apikey", ANON_KEY)
                .addHeader("Authorization", "Bearer " + getAccessToken())
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (response.isSuccessful()) {
                    callback.onSuccess();
                } else {
                    callback.onError(safeReadBody(response));
                }
            }
        });
    }

    // ── Error parser ──
    private String parseErrorMessage(String responseBody, String fallback) {
        try {
            JsonObject err = JsonParser.parseString(responseBody).getAsJsonObject();
            if (err.has("error_description")) return err.get("error_description").getAsString();
            if (err.has("msg")) return err.get("msg").getAsString();
            if (err.has("message")) return err.get("message").getAsString();
            if (err.has("error")) {
                JsonElement errEl = err.get("error");
                if (errEl.isJsonPrimitive()) return errEl.getAsString();
            }
        } catch (Exception ignored) {}
        return fallback;
    }
}