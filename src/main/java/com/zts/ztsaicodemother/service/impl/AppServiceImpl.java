package com.zts.ztsaicodemother.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zts.ztsaicodemother.constant.AppConstant;
import com.zts.ztsaicodemother.core.AiCodeGeneratorFacade;
import com.zts.ztsaicodemother.core.handler.StreamHandlerExecutor;
import com.zts.ztsaicodemother.exception.BusinessException;
import com.zts.ztsaicodemother.exception.ErrorCode;
import com.zts.ztsaicodemother.exception.ThrowUtils;
import com.zts.ztsaicodemother.mapper.AppMapper;
import com.zts.ztsaicodemother.model.dto.app.AppQueryRequest;
import com.zts.ztsaicodemother.model.entity.App;
import com.zts.ztsaicodemother.model.entity.User;
import com.zts.ztsaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.zts.ztsaicodemother.model.enums.CodeGenTypeEnum;
import com.zts.ztsaicodemother.model.vo.AppVO;
import com.zts.ztsaicodemother.model.vo.UserVO;
import com.zts.ztsaicodemother.service.AppService;
import com.zts.ztsaicodemother.service.ChatHistoryService;
import com.zts.ztsaicodemother.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://github.com/ztsupercarry">ztsupercarry</a>
 */
@Service
@Slf4j
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    /**
     * 允许排序的字段，防止 order by 被拼接非法 SQL
     */
    private static final Set<String> SORT_FIELD_WHITE_LIST = Set.of(
            "id", "appName", "cover", "initPrompt", "codeGenType", "deployKey",
            "deployedTime", "priority", "userId", "editTime", "createTime", "updateTime");

    @Resource
    private UserService userService;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;

    @Override
    public Flux<String> chatToGenCode(Long appId, String message, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限访问该应用，仅本人可以生成代码
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }
        // 4. 获取应用的代码生成类型
        String codeGenType = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        }
        // 5. 通过校验后，添加用户消息到对话历史
        chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());
        // 6. 调用 AI 生成代码（流式）
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId);
        // 7. 收集 AI 响应内容并在完成后记录到对话历史
        StringBuilder aiResponseBuilder = new StringBuilder();
        return streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, loginUser, codeGenTypeEnum);
    }

    @Override
    public String deployApp(Long appId, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限部署该应用，仅本人可部署
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限部署该应用");
        }
        // 4. 检查是否已有 deploykey
        String deployKey = app.getDeployKey();
        // 没有则生成 6 位 deploykey（大小写字母 + 数字）
        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }
        // 5. 获取代码生成类型，构建源目录路径
        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
        // 6. 检查源目录是否存在
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
        }
        // 7. 复制文件到部署目录
        String deployDirPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;
        try {
            FileUtil.copyContent(sourceDir, new File(deployDirPath), true);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署失败：" + e.getMessage());
        }
        // 8. 更新应用的 deploykey 和部署时间
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setDeployKey(deployKey);
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean updateResult = this.updateById(updateApp);
        ThrowUtils.throwIf(!updateResult, ErrorCode.OPERATION_ERROR, "更新应用部署信息失败");
        // 9. 返回可访问的 URL
        return String.format("%s/%s/", AppConstant.CODE_DEPLOY_HOST, deployKey);
    }

    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        // 关联查询用户信息
        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            appVO.setUser(userVO);
        }
        return appVO;
    }


    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 批量查询创建用户信息，避免 N + 1 查询
        Set<Long> userIdSet = appList.stream()
                .map(App::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = new HashMap<>();
        if (CollUtil.isNotEmpty(userIdSet)) {
            userService.listByIds(userIdSet)
                    .forEach(user -> userMap.put(user.getId(), user));
        }
        return appList.stream()
                .map(app -> toAppVO(app, userMap.get(app.getUserId())))
                .collect(Collectors.toList());
    }

    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", id) // where id = ${id}
                .like("appName", appName) // and appName like %${appName}%
                .like("cover", cover)
                .like("initPrompt", initPrompt) // 应用初始化的 prompt 一般较长，使用模糊查询
                .eq("codeGenType", codeGenType)
                .eq("deployKey", deployKey)
                .eq("priority", priority)
                .eq("userId", userId);
        // 排序字段必须来自白名单，否则会拼接出非法 SQL；为空时不做排序
        if (StrUtil.isNotBlank(sortField)) {
            if (!SORT_FIELD_WHITE_LIST.contains(sortField)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "排序字段不合法");
            }
            queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
        }
        return queryWrapper;
    }

    /**
     * 删除应用时，关联删除对话历史
     *
     * @param id
     * @return
     */
    public boolean removeById(Serializable id) {
        if (id == null) {
            return false;
        }
        long appId = Long.parseLong(id.toString());
        if (appId <= 0) {
            return false;
        }
        // 先删除关联的对话历史
        try {
            chatHistoryService.deleteByAppId(appId);
        } catch (Exception e) {
            log.error("删除应用关联的对话历史失败：{}", e.getMessage());
        }
        // 删除应用
        return super.removeById(id);
    }

    /**
     * 转换为脱敏后的应用信息
     *
     * @param app  应用信息
     * @param user 创建用户信息
     * @return 脱敏后的应用信息
     */
    private AppVO toAppVO(App app, User user) {
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        appVO.setUser(userService.getUserVO(user));
        return appVO;
    }
}
