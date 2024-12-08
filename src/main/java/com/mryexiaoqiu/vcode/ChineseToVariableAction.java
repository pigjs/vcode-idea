package com.mryexiaoqiu.vcode;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class ChineseToVariableAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Editor editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }

        SelectionModel selectionModel = editor.getSelectionModel();
        String selectedText = selectionModel.getSelectedText();

        if (selectedText == null || selectedText.trim().isEmpty()) {
            Messages.showErrorDialog("内容不能为空", "错误");
            return;
        }

        if (selectedText.length() > 20) {
            Messages.showErrorDialog("最多只能处理20个字", "错误");
            return;
        }

        if (!selectedText.matches("[\\u4e00-\\u9fa5]+")) {
            Messages.showErrorDialog("只能处理中文", "错误");
            return;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return chatCompletion(selectedText);
            } catch (IOException ex) {
                ex.printStackTrace();
                return null;
            }
        }).thenAccept(variableName -> {
            if (variableName != null) {
                WriteCommandAction.runWriteCommandAction(editor.getProject(), () -> {
                    int start = selectionModel.getSelectionStart();
                    int end = selectionModel.getSelectionEnd();

                    if (editor.getDocument().isWritable()) {
                        editor.getDocument().replaceString(start, end, variableName);
                    } else {
                        Messages.showErrorDialog("文件是只读的，无法修改", "错误");
                    }
                });
            } else {
                Messages.showErrorDialog("处理失败，请重试！", "错误");
            }
        });
    }

    private String chatCompletion(String text) throws IOException {
        for (int i = 0; i < 3; i++) {
            try {
                String response = performApiRequest(text);
                if (response != null && !response.matches(".*[\\u4e00-\\u9fa5].*")) {
                    return response;
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        throw new IOException("request error");
    }

    private String performApiRequest(String text) throws IOException {
        HttpClient client = HttpClient.newHttpClient();

        String requestBody = String.format("""
            {
              "model": "",
              "messages": [
                {
                  "role": "user",
                  "content": "你是一个代码命名建议助手，请根据中文名称：”%s“，生成符合命名约定的小驼峰变量名，只要返回生成的变量名就行不需要其他额外的内容"
                }
              ]
            }
            """, text);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://ark.cn-beijing.volces.com/api/v3/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("Request failed with status code: " + response.statusCode());
            }

            String responseBody = response.body();
            return parseResponse(responseBody);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复线程中断状态
            throw new IOException("Request interrupted", e);
        }
    }

    private String parseResponse(String responseBody) {
        String marker = "\"content\":";
        int startIndex = responseBody.indexOf(marker);
        if (startIndex == -1) {
            return null;
        }
        startIndex += marker.length();
        int endIndex = responseBody.indexOf("\"", startIndex + 1);
        if (endIndex == -1) {
            return null;
        }
        return responseBody.substring(startIndex + 1, endIndex).trim();
    }
}