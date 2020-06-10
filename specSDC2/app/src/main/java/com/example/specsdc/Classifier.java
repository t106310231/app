package com.example.specsdc;

public interface Classifier {
    float[][] recognizecolor(float[][] inputData);

    void close();
}
