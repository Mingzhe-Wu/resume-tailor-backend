package com.mingzhe.resumetailor;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Calls the OpenAI chat completion API to generate resume content.
 */
@Service
public class OpenAiService {

    private final String apiKey = System.getenv("OPENAI_API_KEY");

    public String generate(String prompt) {
        try {
            URL url = new URL("https://api.openai.com/v1/chat/completions");

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String safePrompt = prompt
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n");

            String body = """
            {
              "model": "gpt-4o-mini",
              "messages": [
                {"role": "user", "content": "%s"}
              ]
            }
            """.formatted(safePrompt);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes());
            }

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream())
            );

            StringBuilder response = new StringBuilder();
            String line;

            while ((line = br.readLine()) != null) {
                response.append(line);
            }

            String aiResponseRaw = response.toString();

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode root = objectMapper.readTree(aiResponseRaw);

            return root.path("choices")
                    .get(0)
                    .path("message")
                    .path("content").asString();

        } catch (Exception e) {
            e.printStackTrace();
            return "AI call failed";
        }
    }
}
