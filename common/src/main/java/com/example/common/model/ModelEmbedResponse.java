package com.example.common.model;

import java.util.List;
import java.util.Vector;

public record ModelEmbedResponse(List<float[]> vectors,String model, Usage usage) {
}
