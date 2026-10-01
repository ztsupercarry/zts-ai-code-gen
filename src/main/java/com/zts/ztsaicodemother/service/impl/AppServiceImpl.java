package com.zts.ztsaicodemother.service.impl;

import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zts.ztsaicodemother.model.entity.App;
import com.zts.ztsaicodemother.mapper.AppMapper;
import com.zts.ztsaicodemother.service.AppService;
import org.springframework.stereotype.Service;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://github.com/ztsupercarry">ztsupercarry</a>
 */
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App>  implements AppService{

}
