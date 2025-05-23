package com.ai.chat.a.chat.openai;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import com.ai.chat.a.constant.Constants;
import com.ai.chat.a.dto.UserChatDTO;
import com.ai.chat.a.entity.OpenAIResponse;
import com.ai.chat.a.entity.UserIdea;
import com.ai.chat.a.entity.UserUploadFile;
import com.ai.chat.a.image.qianfan.AAIQianfanImageClient;
import com.ai.chat.a.milvus.AVectorDB;
import com.ai.chat.a.redis.RedisUtil;
import com.ai.chat.a.structuredOutput.JSONStructuredOutput;
import com.ai.chat.a.utils.*;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.MimeType;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Slf4j
public class AAIOpenAIChatClient {
       @Autowired
       private OpenAiChatModel chatClient;
       @Autowired
       private AAIQianfanImageClient qianfanImageClient;
       @Autowired
       private AliOssUpload aliOssUpload;
       private String defaultMessage = Constants.DEFAULT_USER_MESSAGE;
       private String defaultModel;
       @Autowired
       private RedisUtil redisUtil;
       @Autowired
       private JSONStructuredOutput jsonStructuredOutput;
       @Autowired
       private AVectorDB aVectorDB;
       /**
        * 当前对话没有记忆功能，实现记忆功能只需要存储上下文对话,且历史记录可能过长导致突破token限制，使用rag技术解决这个问题
        * */


       public OpenAIResponse generateRAG(UserChatDTO userChatDTO,String model,List<String> contents) throws IOException {
           List<UserUploadFile> userUploadFile = redisUtil.getUserUploadFile(userChatDTO);
           String[] upload = new String[1];
           String generateFileName = null;
           String fileSize = null;
           Integer fileType= null;
           ChatResponse response = null;
           Prompt prompt = null;
           String ba64File = "";
           Boolean updated = redisUtil.updateExpirationTime(ThreadLocalUtil.get() + userChatDTO.getSessionId());
           List<Media> mediaList = new ArrayList<>();
           List<String> readMediaFileList = new ArrayList<>();
           if (!updated){
               redisUtil.setUserUploadFile(userChatDTO,userUploadFile);
           }
           UserIdea userIdea = null;
           if(!Constants.N_MODEL.contains(model)){
               userIdea = getUserIdea(userChatDTO.getQuestion());
               log.info("多模态模型");
           }
           if(userUploadFile !=null && !userUploadFile.isEmpty()){
               log.info("上传了文件");
               List<Message> systemMessages = new ArrayList<>();
               contents.forEach(content ->{
                    systemMessages.add(new SystemMessage(content));
               });
               systemMessages.add(new SystemMessage(Constants.SYSTEM_MESSAGE_PROMPT));
               for (UserUploadFile uploadFile : userUploadFile) {
                   if(uploadFile.getFileType() == 1){
                       stringPathToMedia(uploadFile.getSrc(),mediaList);
                       readMediaFileList.add(uploadFile.getReadMediaFile().getPrompt()+","+uploadFile.getReadMediaFile().getDesc());
                   }else{
                        systemMessages.add(new SystemMessage(uploadFile.getReadMediaFile().getDesc()));
                   }
               }
               UserMessage userMessage = new UserMessage("");
                   if(userIdea != null){
                       log.info("得到用户想法");
                       if(userIdea.getGenerateVoice()){
                           // TODO 语音生成
                           ba64File = "";
                       }else if(userIdea.getGenerateImage()){
                           log.info("图片生成");
                           // TODO 图片生成
                           String prompts = String.join(",", readMediaFileList);
                           ba64File = qianfanImageClient.getImageFromQianfanSDXL(
                                   prompts+","
                                           + userIdea.getPrompt())+","+userIdea.getStyle();
                           log.info("ba64File{}",ba64File);
                           generateFileName = IdUtil.simpleUUID();
                           upload = aliOssUpload.upload(Base64ToMultipartFileConverter.base64ToMultipartFile(ba64File, generateFileName));
                           stringPathToMedia(upload[0],mediaList);
                           userMessage = new UserMessage(Constants.FILE_TO_FILE,mediaList);
                           MultipartFile multipartFile = Base64ToMultipartFileConverter.base64ToMultipartFile(ba64File, generateFileName);
                           fileSize = FileUtil.formatFileSize(multipartFile.getSize());
                           fileType= FileUtil.getFileType(multipartFile);
                       }else{
                           log.info("想法普通问题");
                           userMessage =  new UserMessage(userChatDTO.getQuestion(),mediaList);
                       }
                   }else {
                       log.info("无想法普通问题");
                       userMessage =  new UserMessage(userChatDTO.getQuestion(),mediaList);
                   }
                   systemMessages.add(userMessage);
                   prompt = new Prompt(systemMessages,OpenAiChatOptions.builder()
                           .withModel(setDefault(model))
                           .withHttpHeaders(Map.of("Accept-Encoding","identity"))
                           .build());
                   response = chatClient.call(prompt);
           }else {
               UserMessage userMessage = null;
               log.info("没有上传文件");
               ArrayList<Message> messages = new ArrayList<>();
               contents.forEach(content->{
                  messages.add(new SystemMessage(content));
               });
               messages.add(new SystemMessage(Constants.SYSTEM_MESSAGE_PROMPT));
               if(userIdea != null){
                   if(userIdea.getGenerateVoice()){
                       // TODO 语音生成
                       ba64File = "";
                   }else if(userIdea.getGenerateImage()){
                       log.info("图片生成");
                       // TODO 图片生成
                       ba64File = qianfanImageClient.getImageFromQianfanSDXL(userIdea.getPrompt()+","+userIdea.getStyle());
                       if(ba64File.isEmpty()){
                           return null;
                       }
                       log.info("普通问题无文件上传生成文件");
                       generateFileName = IdUtil.simpleUUID();
                       upload=  aliOssUpload.upload(Base64ToMultipartFileConverter.base64ToMultipartFile(ba64File, generateFileName));
                       stringPathToMedia(upload[0],mediaList);
                       userMessage = new UserMessage(Constants.GET_FILE_CONTENT_PROMPT+" "+userChatDTO.getQuestion(),mediaList);
                       MultipartFile multipartFile = Base64ToMultipartFileConverter.base64ToMultipartFile(ba64File, generateFileName);
                       fileSize = FileUtil.formatFileSize(multipartFile.getSize());
                       fileType= FileUtil.getFileType(multipartFile);
                   }else{
                       userMessage =  new UserMessage(userChatDTO.getQuestion());
                   }
               }else {
                   log.info("普通问题");
                   userMessage =  new UserMessage(userChatDTO.getQuestion());
               }
               messages.add(userMessage);
               prompt = new Prompt(messages,OpenAiChatOptions
                       .builder()
                       .withModel(setDefault(model))
                       .withHttpHeaders(Map.of("Accept-Encoding","identity"))
                       .build());
               log.info("prompt:{}", prompt.toString());
               response = chatClient.call(prompt);
           }

           return OpenAIResponse.builder()
                   .response(response
                           .getResult()
                           .getOutput()
                           .getContent())
                   .fileType(fileType)
                   .fileName(generateFileName)
                   .fileSize(fileSize)
                   .filePath(upload[0])
                   .build();
       }






       public AAIOpenAIChatClient(String defaultModel){
               this.defaultModel = defaultModel;
       }
       public OpenAIResponse generate(UserChatDTO userChatDTO, String model) throws IOException {
           List<UserUploadFile> userUploadFile = redisUtil.getUserUploadFile(userChatDTO);
           Boolean updated = redisUtil.updateExpirationTime(ThreadLocalUtil.get() + userChatDTO.getSessionId());
           if (!updated){
               redisUtil.setUserUploadFile(userChatDTO,userUploadFile);
           }
           Prompt prompt = null;
           ChatResponse response = null;
           String ba64File = "";
           String[] upload = new String[1];
           List<Media> mediaList = new ArrayList<>();
           UserIdea userIdea = null;
           String generateFileName = null;
           String fileSize = null;
           Integer fileType= null;
           List<String> readMediaFileList = new ArrayList<>();
           if(!Constants.N_MODEL.contains(model)){
              userIdea = getUserIdea(userChatDTO.getQuestion());
              log.info("多模态模型");
           }
           if(userUploadFile !=null && !userUploadFile.isEmpty()){
               log.info("上传了文件");
               SystemMessage systemMessage = new SystemMessage("");
               for (UserUploadFile uploadFile : userUploadFile) {
                   if(uploadFile.getFileType() == 1){
                       stringPathToMedia(uploadFile.getSrc(),mediaList);
                       readMediaFileList.add(uploadFile.getReadMediaFile().getPrompt()+","+uploadFile.getReadMediaFile().getDesc());
                   }else {
                       systemMessage = new SystemMessage(uploadFile.getReadMediaFile().getDesc());
                   }
               }
               UserMessage userMessage = new UserMessage("");
               if(userIdea != null){
                   log.info("得到用户想法");
                   if(userIdea.getGenerateVoice()){
                       // TODO 语音生成
                        ba64File = "";
                   }else if(userIdea.getGenerateImage()){
                       log.info("图片生成");
                       // TODO 图片生成
                       String prompts = String.join(",", readMediaFileList);
                       ba64File = qianfanImageClient.getImageFromQianfanSDXL(
                                prompts+","
                                      + userIdea.getPrompt())+","+userIdea.getStyle();
                       log.info("ba64File{}",ba64File);
                       generateFileName = IdUtil.simpleUUID();
                       upload = aliOssUpload.upload(Base64ToMultipartFileConverter.base64ToMultipartFile(ba64File, generateFileName));
                       stringPathToMedia(upload[0],mediaList);
                       userMessage = new UserMessage(Constants.FILE_TO_FILE,mediaList);
                       MultipartFile multipartFile = Base64ToMultipartFileConverter.base64ToMultipartFile(ba64File, generateFileName);
                       fileSize = FileUtil.formatFileSize(multipartFile.getSize());
                       fileType= FileUtil.getFileType(multipartFile);
                   }else{
                       log.info("想法普通问题");
                       userMessage =  new UserMessage(userChatDTO.getQuestion(),mediaList);
                   }
               }else {
                     log.info("无想法普通问题");
                     userMessage =  new UserMessage(userChatDTO.getQuestion(),mediaList);
               }
               prompt = new Prompt(List.of(systemMessage,userMessage),OpenAiChatOptions.builder()
                       .withModel(setDefault(model))
                       .withHttpHeaders(Map.of("Accept-Encoding","identity"))
                       .build());
               response = chatClient.call(prompt);

           }else {
               UserMessage userMessage = null;
               log.info("没有上传文件");
               if(userIdea != null){
                   if(userIdea.getGenerateVoice()){
                       // TODO 语音生成
                       ba64File = "";
                   }else if(userIdea.getGenerateImage()){
                       log.info("图片生成");
                       // TODO 图片生成
                       ba64File = qianfanImageClient.getImageFromQianfanSDXL(userIdea.getPrompt()+","+userIdea.getStyle());
                       if(ba64File.isEmpty()){
                            return null;
                       }
                       log.info("普通问题无文件上传生成文件");
                       generateFileName = IdUtil.simpleUUID();
                       upload=  aliOssUpload.upload(Base64ToMultipartFileConverter.base64ToMultipartFile(ba64File, generateFileName));
                     stringPathToMedia(upload[0],mediaList);
                     userMessage = new UserMessage(Constants.GET_FILE_CONTENT_PROMPT+" "+userChatDTO.getQuestion(),mediaList);
                     MultipartFile multipartFile = Base64ToMultipartFileConverter.base64ToMultipartFile(ba64File, generateFileName);
                     fileSize = FileUtil.formatFileSize(multipartFile.getSize());
                     fileType= FileUtil.getFileType(multipartFile);
                   }else{
                       userMessage =  new UserMessage(userChatDTO.getQuestion());
                   }
               }else {
                   log.info("普通问题");
                   userMessage =  new UserMessage(userChatDTO.getQuestion());
               }
               prompt = new Prompt(userMessage,OpenAiChatOptions
                       .builder()
                       .withModel(setDefault(model))
                       .withHttpHeaders(Map.of("Accept-Encoding","identity"))
                       .build());
               log.info("prompt:{}", prompt.toString());
               response = chatClient.call(prompt);
           }
           return OpenAIResponse.builder()
                   .response(response
                           .getResult()
                           .getOutput()
                           .getContent())
                   .fileType(fileType)
                   .fileName(generateFileName)
                   .fileSize(fileSize)
                   .filePath(upload[0])
                   .build();
       }
       public Flux<ChatResponse> generateStream(UserChatDTO userChatDTO, String model) throws IOException {
              // TODO 流式生成
              return null;
       }

       private String setDefault(String model) {
              if(model == null || model.isEmpty()) {
                    model = defaultModel;
              }
              return model;
       }
     private UserIdea getUserIdea(String prompt){
         UserIdea userIdea = jsonStructuredOutput.userIdeaOutput(prompt);
         log.info("userIdea:{}", userIdea);
         if(userIdea == null){
             userIdea = handleUserIdea(prompt);
         }
         return userIdea;
     }
    private UserIdea handleUserIdea(String idea){
            return JSONObject.parseObject("""
                    {                "generateImage":false,
                                    "generateVideo":false,
                                    "generateVoice":false,
                                    "style":"",
                                    "prompt":""
                                }""",UserIdea.class);
    }
    private void stringPathToMedia(String path,List<Media> mediaList) throws MalformedURLException {
                log.info(path);
                log.info(cn.hutool.core.io.FileUtil.getMimeType(path));
                log.info(new URL(path).toString());
                Media media = new Media(new MimeType(cn.hutool.core.io.FileUtil.getMimeType(path).split("/")[0]), new URL(path));
                mediaList.add(media);

    }

}
