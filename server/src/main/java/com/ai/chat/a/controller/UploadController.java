package com.ai.chat.a.controller;

import cn.hutool.core.util.IdUtil;
import com.ai.chat.a.api.aiCoreAPI.dto.GenerateLyricsPromptDTO;
import com.ai.chat.a.api.aiCoreAPI.util.Request;
import com.ai.chat.a.api.gcuiArtAPI.dto.SunoFastDTO;
import com.ai.chat.a.api.gcuiArtAPI.util.RequestGcui;
import com.ai.chat.a.constant.Constants;
import com.ai.chat.a.dto.MessageSendDTO;
import com.ai.chat.a.entity.ReadMediaFile;
import com.ai.chat.a.entity.UserUploadFile;
import com.ai.chat.a.enums.ErrorCode;
import com.ai.chat.a.enums.MessageTypeEnum;
import com.ai.chat.a.enums.UploadEnum;
import com.ai.chat.a.enums.UserRobotTypeEnum;
import com.ai.chat.a.handle.FileHandle;
import com.ai.chat.a.html.TimeOut;
import com.ai.chat.a.milvus.AVectorDB;
import com.ai.chat.a.mq.MessageHandle;
import com.ai.chat.a.po.Session;
import com.ai.chat.a.po.User;
import com.ai.chat.a.po.UserDocument;
import com.ai.chat.a.redis.RedisComponent;
import com.ai.chat.a.result.R;
import com.ai.chat.a.service.SessionService;
import com.ai.chat.a.service.UserDocumentService;
import com.ai.chat.a.service.UserService;
import com.ai.chat.a.utils.AliOssUpload;
import com.ai.chat.a.utils.FileUtil;
import com.ai.chat.a.utils.ThreadLocalUtil;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SerializationUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
@Slf4j
public class UploadController {
    private final FileHandle fileHandle;
    private final AliOssUpload aliOssUpload;
    private final RedisComponent redisComponent;
    private final SessionService sessionService;
    private final MessageHandle messageHandle;
    private final UserService userService;
    private final  AVectorDB aVectorDB;
    private final UserDocumentService userDocumentService;
    private final RequestGcui requestGcui;
    private final AtomicBoolean shouldTerminate = new AtomicBoolean(false);

    //TODO 逻辑复杂，拆分代码
    //TODO openai接口好像不能直接处理图片以外的文件，将其他文件提取为纯文本
    @PostMapping("/openai/{model}")
    public R uploadAttachment(@RequestParam List<MultipartFile> files, @PathVariable String model, @RequestParam String session) throws IOException {
        log.info("userId {}", ThreadLocalUtil.get()+"");
        Session currentSession = JSONObject.parseObject(session, Session.class);
        log.info("currentSession:{}",currentSession.toString());
        List<UserUploadFile> showUploadFileList;
        String uploadFileFromRedis = redisComponent.getUploadFileFromRedis(ThreadLocalUtil.get() + currentSession.getSessionId());
        List<UserUploadFile> userUploadFiles = new ArrayList<>();
        boolean hasFile = false;
        int size = 0;
        if(uploadFileFromRedis !=null){
            hasFile = true;
            userUploadFiles = JSONObject.parseArray(uploadFileFromRedis, UserUploadFile.class);
            size = userUploadFiles.size();
            if(size>=4 || files.size()+size>4){
                return R.success(String.valueOf(UploadEnum.FAIL_OVER_NUM.getCode()),userUploadFiles);
            }
        }else if(files.size()>4){
            return R.success(String.valueOf(UploadEnum.FAIL_OVER_NUM.getCode()),userUploadFiles);
        }



        if(!Constants.N_MODEL.contains(model)){

            String fileId = IdUtil.simpleUUID();
            shouldTerminate.set(false);
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(files.size(), 4));
            List<Future<UserUploadFile>> futures = new ArrayList<>();




            CompletableFuture<R> timeoutFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    TimeUnit.SECONDS.sleep(50);
                    // TODO: 调用 RabbitMQ 推送消息到前端
                    User user = userService.getOne(new LambdaQueryWrapper<User>().eq(User::getId, ThreadLocalUtil.get()));
                    MessageSendDTO messageSendDTO = MessageSendDTO.builder()
                            .sendUserId(currentSession.getRobotId())
                            .sendUserNickName(currentSession.getRobotName())
                            .contactName(user.getUsername())
                            .contactId(user.getId())
                            .contactName(user.getUsername())
                            .contactType(UserRobotTypeEnum.ROBOT.getType())
                            .messageType(MessageTypeEnum.UPLOAD_TIME_OUT.getType())
                            .lastMessage("文件上传超时")
                            .messageContent(TimeOut.TIME_OUT)
                            .sessionId(currentSession.getSessionId())
                            .build();
                    messageHandle.sendMessage(messageSendDTO);
                    return R.error(ErrorCode.UPLOAD_ERROR.getCode(), "超时，请稍后再试");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return R.error(ErrorCode.UPLOAD_ERROR.getCode(), "超时处理失败");
                }
            });



            for (MultipartFile file : files) {
                if(shouldTerminate.get()){
                    sessionService.updateSession4UploadFail(currentSession,Constants.NO_AUTHOR);
                    return R.error(ErrorCode.UPLOAD_ERROR.getCode(),"all");
                }
                futures.add(executor.submit(()->{
                    ReadMediaFile readMediaFile = fileHandle.handleMedia(file, model);
                    if(readMediaFile !=null){
                        if(readMediaFile.getDesc().equals(Constants.NO_AUTHOR)){
                            shouldTerminate.set(true);
                             return UserUploadFile.builder().name(file.getOriginalFilename()).status(UploadEnum.AUTH_FAIL.getCode()).build();
                        }
                        try{
                            String[] upload = aliOssUpload.upload(file);
                            return UserUploadFile.builder()
                                    .src(upload[0])
                                    .name(file.getOriginalFilename())
                                    .fileSize(FileUtil.formatFileSize(file.getSize()))
                                    .fileType(FileUtil.getFileType(file))
                                    .userId(ThreadLocalUtil.get())
                                    .uploadTime(LocalDateTime.now())
                                    .status(UploadEnum.SUCCESS.getCode())
                                    .sessionId(currentSession.getSessionId())
                                    .fileId(fileId)
                                    .readMediaFile(readMediaFile)
                                    .build();
                        }catch (Exception e){
                            return UserUploadFile.builder().name(file.getOriginalFilename()).status(UploadEnum.FAIL.getCode()).build();
                        }
                        // TODO: 立即返回文件信息
                    }else {
                        log.info("文件类型错误");
                        sessionService.updateSession4UploadFail(currentSession,Constants.UPLOAD_FILE_TYPE);
                        return UserUploadFile.builder().status(UploadEnum.FAIL.getCode()).name(file.getOriginalFilename()).build();

                    }
                }));
            }
            ArrayList<UserUploadFile> userUploadFileList = new ArrayList<>();
            showUploadFileList = new ArrayList<>(userUploadFiles);
            for (Future<UserUploadFile> future : futures) {
                if(shouldTerminate.get()){
                     break;
                }
                try {
                    userUploadFileList.add(future.get()); // 按顺序添加结果
                } catch (InterruptedException | ExecutionException e) {
                    userUploadFileList.add(UserUploadFile.builder().status(UploadEnum.FAIL.getCode()).build());
                }
            }
            executor.shutdown();
            showUploadFileList.addAll(userUploadFileList);

            if(!timeoutFuture.isDone()){
                 timeoutFuture.cancel(true);
            }

            List<UserUploadFile> saveUploadFileList = SerializationUtils.clone(userUploadFileList);
            List<UserUploadFile> userUploadFileFilter = saveUploadFileList.stream().filter(item -> !Objects.equals(item.getStatus(), UploadEnum.FAIL.getCode())).toList();
            if(hasFile){
                log.info("userUploadFiles:{}",userUploadFiles);
                userUploadFiles.addAll(userUploadFileFilter);
                userUploadFileFilter = userUploadFiles;
            }
            log.info("userUploadFileList:{}",userUploadFileFilter);
            String jsonString = JSONObject.toJSONString(userUploadFileFilter);
            redisComponent.saveUploadFileInRedis(ThreadLocalUtil.get()+currentSession.getSessionId(),jsonString);
            log.info("返回值{}   ",userUploadFileList);
        }else{
          // TODO: 新增机器人对话“非常抱歉我无法处理文件，请换一个机器人”
               sessionService.updateSession4UploadFail(currentSession,Constants.CAN_NOT_UPLOAD);
               files.clear();
               return R.error(ErrorCode.UPLOAD_ERROR.getCode(),"all");
            // TODO: 通过Mq将此信息发送到websocket推送到前端
        }
        if(shouldTerminate.get()){
            sessionService.updateSession4UploadFail(currentSession,Constants.NO_AUTHOR);
             return R.error(ErrorCode.UPLOAD_ERROR.getCode(),"all");
        }
        return R.success(showUploadFileList);
    }

    @PostMapping("/openai/rag/{model}")
    public R uploadAttachmentRag(@RequestParam List<MultipartFile> files, @PathVariable String model,@RequestParam String session){
        //TODO 对上传的文本类型文件进行向量化处理，存储，和ai分析
        log.info("userId {}", ThreadLocalUtil.get()+"");
        Session currentSession = JSONObject.parseObject(session, Session.class);
        log.info("currentSession:{}",currentSession.toString());
        List<UserUploadFile> showUploadFileList = new ArrayList<>();
        String uploadFileFromRedis = redisComponent.getUploadFileFromRedis(ThreadLocalUtil.get() + currentSession.getSessionId());
        List<UserUploadFile> userUploadFiles = new ArrayList<>();
        boolean hasFile = false;
        int size = 0;
        if(uploadFileFromRedis !=null){
            hasFile = true;
            userUploadFiles = JSONObject.parseArray(uploadFileFromRedis, UserUploadFile.class);
            size = userUploadFiles.size();
            if(size>=4 || files.size()+size>4){
                return R.success(String.valueOf(UploadEnum.FAIL_OVER_NUM.getCode()),userUploadFiles);
            }
        }else if(files.size()>4){
            return R.success(String.valueOf(UploadEnum.FAIL_OVER_NUM.getCode()),userUploadFiles);
        }

        if(!Constants.N_MODEL.contains(model)){
            String fileId = IdUtil.simpleUUID();
            shouldTerminate.set(false);
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(files.size(), 4));
            List<Future<UserUploadFile>> futures = new ArrayList<>();
            CompletableFuture<R> timeoutFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    TimeUnit.SECONDS.sleep(50);
                    // TODO: 调用 RabbitMQ 推送消息到前端
                    User user = userService.getOne(new LambdaQueryWrapper<User>().eq(User::getId, ThreadLocalUtil.get()));
                    MessageSendDTO messageSendDTO = MessageSendDTO.builder()
                            .sendUserId(currentSession.getRobotId())
                            .sendUserNickName(currentSession.getRobotName())
                            .contactName(user.getUsername())
                            .contactId(user.getId())
                            .contactName(user.getUsername())
                            .contactType(UserRobotTypeEnum.ROBOT.getType())
                            .messageType(MessageTypeEnum.UPLOAD_TIME_OUT.getType())
                            .lastMessage("文件上传超时")
                            .messageContent(TimeOut.TIME_OUT)
                            .sessionId(currentSession.getSessionId())
                            .build();
                    messageHandle.sendMessage(messageSendDTO);
                    return R.error(ErrorCode.UPLOAD_ERROR.getCode(), "超时，请稍后再试");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return R.error(ErrorCode.UPLOAD_ERROR.getCode(), "超时处理失败");
                }
            });



             for (MultipartFile file : files){
                 if(shouldTerminate.get()){
                     sessionService.updateSession4UploadFail(currentSession,Constants.NO_AUTHOR);
                     return R.error(ErrorCode.UPLOAD_ERROR.getCode(),"all");
                 }
                futures.add(executor.submit(()->{
                    ReadMediaFile readMediaFile = ReadMediaFile.defaultReadMediaFile();
                    int fileType = FileUtil.getFileType(file);
                    if(List.of(3,7,8,9,10,5).contains(fileType)){
                        List<String> ids = aVectorDB.addDocument(file.getResource());
                        if(ids != null){
                             List<UserDocument> userDocumentList = new ArrayList<>();
                             ids.forEach(id->{
                                  userDocumentList.add(UserDocument.builder()
                                          .documentId(id)
                                          .userId(ThreadLocalUtil.get())
                                          .sessionId(currentSession.getSessionId())
                                          .build());
                             });
                             userDocumentService.saveBatch(userDocumentList);
                        }
                        readMediaFile.setFetch(true);
                        if(ids != null){
                             return UserUploadFile.builder()
                                     .status(UploadEnum.FAIL.getCode())
                                     .name(file.getOriginalFilename())
                                     .build();
                        }
                    }else {
                        readMediaFile = fileHandle.handleMedia(file, model);
                        if(readMediaFile != null){
                            if(readMediaFile.getDesc().equals(Constants.NO_AUTHOR)){
                                shouldTerminate.set(true);
                                return UserUploadFile.builder().name(file.getOriginalFilename()).status(UploadEnum.AUTH_FAIL.getCode()).build();
                            }
                        }else{
                            log.info("文件类型错误");
                            sessionService.updateSession4UploadFail(currentSession,Constants.UPLOAD_FILE_TYPE);
                            return UserUploadFile.builder().status(UploadEnum.FAIL.getCode()).name(file.getOriginalFilename()).build();
                        }
                    }
                    try{
                        String[] upload = aliOssUpload.upload(file);
                        return UserUploadFile.builder()
                                .src(upload[0])
                                .name(file.getOriginalFilename())
                                .fileSize(FileUtil.formatFileSize(file.getSize()))
                                .fileType(FileUtil.getFileType(file))
                                .userId(ThreadLocalUtil.get())
                                .uploadTime(LocalDateTime.now())
                                .status(UploadEnum.SUCCESS.getCode())
                                .sessionId(currentSession.getSessionId())
                                .fileId(fileId)
                                .readMediaFile(readMediaFile)
                                .build();
                    }catch (Exception e){
                        return UserUploadFile.builder().name(file.getOriginalFilename()).status(UploadEnum.FAIL.getCode()).build();
                    }
                }));
             }

            ArrayList<UserUploadFile> userUploadFileList = new ArrayList<>();
            showUploadFileList = new ArrayList<>(userUploadFiles);
            for (Future<UserUploadFile> future : futures) {
                if(shouldTerminate.get()){
                    break;
                }
                try {
                    userUploadFileList.add(future.get()); // 按顺序添加结果
                } catch (InterruptedException | ExecutionException e) {
                    userUploadFileList.add(UserUploadFile.builder().status(UploadEnum.FAIL.getCode()).build());
                }
            }
            executor.shutdown();
            showUploadFileList.addAll(userUploadFileList);
            if(!timeoutFuture.isDone()){
                timeoutFuture.cancel(true);
            }

            List<UserUploadFile> saveUploadFileList = SerializationUtils.clone(userUploadFileList);
            List<UserUploadFile> userUploadFileFilter = saveUploadFileList.stream().filter(item -> !Objects.equals(item.getStatus(), UploadEnum.FAIL.getCode())).toList();
            if(hasFile){
                log.info("userUploadFiles:{}",userUploadFiles);
                userUploadFiles.addAll(userUploadFileFilter);
                userUploadFileFilter = userUploadFiles;
            }
            log.info("userUploadFileList:{}",userUploadFileFilter);
            String jsonString = JSONObject.toJSONString(userUploadFileFilter);
            redisComponent.saveUploadFileInRedis(ThreadLocalUtil.get()+currentSession.getSessionId(),jsonString);
            log.info("返回值{}   ",userUploadFileList);
        }else{
            sessionService.updateSession4UploadFail(currentSession,Constants.CAN_NOT_UPLOAD);
            files.clear();
            return R.error(ErrorCode.UPLOAD_ERROR.getCode(),"all");
        }
        if(shouldTerminate.get()){
            sessionService.updateSession4UploadFail(currentSession,Constants.NO_AUTHOR);
            return R.error(ErrorCode.UPLOAD_ERROR.getCode(),"all");
        }
        return R.success(showUploadFileList);
    }
//    @PostMapping("/rag/test")
//    public R test() {
////        aVectorDB.addDocument(file.getResource());
////        UserIdea userIdea = JSONStructuredOutput.userIdeaOutput("一段动听的音乐，激情的金属声,是一个电影片段");
////          request.lyricsRequest(GenerateLyricsPromptDTO.builder().prompt("一段动听的音乐，激情的金属声,是一个电影片段").build());
////        request.songRequest(InspirationModePromptDTO.builder().gpt_description_prompt("一段动听的音乐，激情的金属声,是一个电影片段").build());
//          requestGcui.GenerateSongRequest(SunoFastDTO.builder().prompt("孤独之旅").build(),ThreadLocalUtil.get(),"5ad381d9faef1d59864fac10a9194e38");
//          return R.success();
//    }
//    @GetMapping("/test")
//    public R test2(){
//        requestGcui.getGenerateSongRequest("7711c6ad-5487-49dc-80e2-19b56468a94a",0,ThreadLocalUtil.get(),"5ad381d9faef1d59864fac10a9194e38");
//        return R.success();
//    }
@PostMapping("/avatar")
    public R uploadAvatar(@RequestParam("avatar") MultipartFile file){
        try{
            String[] upload = aliOssUpload.upload(file);
            log.info("上传成功{}",upload[0]);
            return R.success(upload);
        }catch (Exception e){
                return R.error(ErrorCode.UPLOAD_ERROR.getCode(),"上传失败");
        }
    }
    @DeleteMapping("/avatar")
    public R deleteAvatar(String fileName){
        try{
            log.info("删除{}",fileName);
            aliOssUpload.delete(fileName);
        }catch (Exception e){
             return R.error(ErrorCode.OPERATION_ERROR.getCode(),"删除失败");
        }
        return R.success();
    }
}
