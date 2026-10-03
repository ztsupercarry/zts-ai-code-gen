package com.zts.ztsaicodemother.constant;

/**
 * 应用常量
 */
public interface AppConstant {

    /**
     * 精选应用的门槛优先级，优先级大于该值即视为精选应用
     */
    Integer GOOD_APP_PRIORITY = 99;

    /**
     * 默认应用优先级
     */
    Integer DEFAULT_APP_PRIORITY = 0;

    /**
     * 应用生成目录
     */
    String CODE_OUTPUT_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_output";

    /**
     * 应用部署目录
     */
    String CODE_DEPLOY_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_deploy";

    /**
     * 应用部署域名
     */
    String CODE_DEPLOY_HOST = "http://localhost";

    /**
     * 普通用户分页查询时，每页最多的应用数量
     */
    Integer MAX_PAGE_SIZE = 20;

    /**
     * 创建应用时，默认取 initPrompt 的前 N 位作为应用名称
     */
    Integer DEFAULT_APP_NAME_LENGTH = 12;

    /**
     * 应用名称的最大长度（与 app 表 appName 字段的长度保持一致）
     */
    Integer APP_NAME_MAX_LENGTH = 256;
}
