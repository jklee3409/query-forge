package io.queryforge.backend.rag.chat.answer.dto;

import java.util.List;

public final class ChatAnswerDtos {

    private ChatAnswerDtos() {
    }

    public record GeneratedAnswer(
            String answerText,
            List<String> citedDocumentIds,
            List<String> citedChunkIds,
            String modelName
    ) {
    }
}
