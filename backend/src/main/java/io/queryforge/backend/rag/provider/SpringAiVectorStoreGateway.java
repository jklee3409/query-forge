package io.queryforge.backend.rag.provider;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.vectorstore.VectorStoreRetriever;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringAiVectorStoreGateway {

    private final ObjectProvider<VectorStoreRetriever> vectorStoreRetrieverProvider;

    public boolean isAvailable() {
        return vectorStoreRetrieverProvider.getIfAvailable() != null;
    }
}
