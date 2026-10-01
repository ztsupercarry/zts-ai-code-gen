package com.zts.ztsaicodemother.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zts.ztsaicodemother.core.AiCodeGeneratorFacade;
import com.zts.ztsaicodemother.exception.BusinessException;
import com.zts.ztsaicodemother.exception.ErrorCode;
import com.zts.ztsaicodemother.exception.ThrowUtils;
import com.zts.ztsaicodemother.mapper.AppMapper;
import com.zts.ztsaicodemother.model.dto.app.AppQueryRequest;
import com.zts.ztsaicodemother.model.entity.App;
import com.zts.ztsaicodemother.model.entity.User;
import com.zts.ztsaicodemother.model.enums.CodeGenTypeEnum;
import com.zts.ztsaicodemother.model.vo.AppVO;
import com.zts.ztsaicodemother.model.vo.UserVO;
import com.zts.ztsaicodemother.service.AppService;
import com.zts.ztsaicodemother.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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
        // 5. 调用 AI 生成代码
        return aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId);
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
