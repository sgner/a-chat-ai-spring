package com.ai.chat.a.api.aiCoreAPI.response;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class LyricsResponse extends SunoResponse{
 private String data;
}
