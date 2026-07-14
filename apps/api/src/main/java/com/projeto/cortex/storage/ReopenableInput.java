package com.projeto.cortex.storage;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface ReopenableInput {

    InputStream open() throws IOException;
}
