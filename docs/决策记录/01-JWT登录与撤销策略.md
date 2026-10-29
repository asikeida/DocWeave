# JWT 登录与撤销策略

> 对应 Issue #13，适用 M1.1。本轮按用户确认的 JWT 方向实现；不引入 Redis 或独立认证服务器。

## 决策与原因

使用 Spring Security、账号密码、短期 JWT Access Token、随机刷新凭据和 MySQL 登录记录。Spring 的资源服务器组件负责 JWT 的标准验证，不手写 JWT 编解码算法。

本项目现在是浏览器应用，Session 也能满足需求。选择 JWT 是为了与后续多种调用端的令牌访问方式一致，同时保留可撤销登录；这不是完全无状态的认证系统，也没有在本轮实现 OAuth 授权服务器或企业 SSO。

## 身份、权限和有效期

- Access Token 默认 5 分钟，最长允许配置到 15 分钟。
- 登录记录固定 7 天到期，刷新不无限续期；到期必须重新输入密码。
- JWT 包含 issuer、audience、subject、issued-at、not-before、expiry、jti、sid 和 token_use=access，不保存密码、部门、组织角色或知识库名单。
- 每次受保护 API 请求在 JWT 标准验证后查询登录记录与当前账号状态。退出撤销 sid 后，旧 JWT 即使未过期也被拒绝；数据库核验失败不会放行。
- JWT 确认全局身份；企业成员、角色和知识权限将在后续模块按当前状态核验，不能把身份认证当作企业授权完成。

## 密码与密钥

- 密码采用带算法前缀的 BCrypt 哈希，当前工作因子为 12，不保存明文。
- 账号标识为 3—64 位 ASCII 字母、数字、点、下划线或连字符，首位必须为字母或数字，统一转小写。显示名独立于登录标识。
- 开发账号密码至少 12 字符，BCrypt 输入最多 72 个 UTF-8 字节；超限不会静默截断。
- JWT 固定使用 HS256，验证 issuer、audience、时间和令牌用途；生产通过环境变量 DOCWEAVE_JWT_SECRET 提供至少 256 位随机密钥的标准 Base64 编码。
- prod 不允许缺失密钥或关闭安全 Cookie。local 缺失密钥时在根目录被忽略的 `.env.auth-signing-key` 生成并复用开发密钥；该文件仅存密钥 Base64，不是供 Shell 执行的环境脚本。test 使用内存随机密钥。
- 当前不实现多密钥并行轮换。更换签名密钥使旧访问令牌不可验证；若需强制所有人重新登录，还必须撤销全部旧登录记录，不能只换密钥却允许旧刷新凭据换取新令牌。

## 刷新轮换与重放

刷新凭据是 32 随机字节的 Base64URL 字符串，不是 JWT。数据库只保存 SHA-256，不保存原值。

每次刷新在事务和行锁内消费旧凭据，生成新凭据；保留旧哈希识别重放。已消费凭据再次使用会撤销整次登录，包括刚发出的访问令牌。该预期失败必须提交撤销结果，不能因抛认证异常回滚。

浏览器通过 Web Locks 串行执行同源标签页的登录、刷新与退出，同标签页恢复请求也合并为一次。使用最新 Chrome/Edge 等支持 Web Locks 的环境验证；不支持该 API 的浏览器仍保留重放拒绝，尚未保证多标签页并发刷新体验。其他 API 调用方必须自行串行刷新。

网络超时不自动重试刷新请求，因为服务器可能已经消费旧凭据。响应丢失时可能需要重新登录，首版不放宽重放规则来掩盖这种情况。

## 浏览器与 CSRF

- 访问令牌只放前端内存，不进入 localStorage、sessionStorage、URL 或控制台。
- 刷新凭据放 `docweave_refresh` Cookie：HttpOnly、SameSite=Strict、路径 `/api/v1/auth`、不设置 Domain；prod 必须 Secure，local 的 HTTP 仅开发使用。
- GET `/api/v1/auth/csrf` 返回掩码 CSRF 令牌及请求头名称。独立 CSRF Cookie 也是 HttpOnly，生产名称使用 __Host- 前缀。
- 登录、刷新与退出等 auth 路径的非安全方法必须通过 Spring Security CSRF 校验；浏览器先取得 CSRF，再在 X-XSRF-TOKEN 请求头提交。
- CSRF 范围通过 Spring Security 路径匹配器判定，不用原始 URI 字符串前缀；编码后的 auth 路径同样必须校验，避免与框架解码后的路由规则不一致。
- 其他业务 API 只接受 Authorization Bearer，不从刷新 Cookie 自动建立登录，因此不依靠该 Cookie 授权业务写操作。
- 保持同域代理，不开放任意来源 CORS。HttpOnly 降低凭据直接被脚本读取的风险，不意味着 XSS 可以忽略。
- 认证响应 no-store；退出/账号切换通过 BroadcastChannel 通知其他标签页清理内存，不广播任何令牌。

## 首账号与注册

本轮不开放注册、密码找回、个人组织初始化或企业成员接口。账号没有全局 ADMIN 角色。

仅 local profile 下显式启用 `docweave.bootstrap.enabled=true` 时可从当前进程环境读取初始化用户名和密码。可以用非 Web 模式执行一次后退出；已存在账号不会覆盖密码。prod 不加载这个入口，也不存在公开初始化 HTTP 接口。

登录接口使用有界内存表，按实际连接来源地址限制每分钟尝试数，默认 20 次；不信任任意 X-Forwarded-For。该限制是单实例保护，重启、多实例或反向代理场景需要网关/可信代理策略，不能当作完整生产反滥用方案。

## 明确限制

- 本轮只撤销当前登录，不提供多设备管理、全部退出或密码变更界面。
- 过期会话及已消费刷新哈希暂未自动清理；记录需保留到对应登录过期以检测重放，公开运营前补充有保留边界的清理任务。
- 单实例本地开发密钥不是生产密钥管理方案；未来拆分签发与验证服务时重新评审非对称签名及轮换。
- M1.2 完成个人组织初始化与反滥用后再开放注册；不能让用户注册成功后没有自己的区域。

## 参考

- [Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [RFC 9700 刷新凭据保护](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14)

参考协议安全建议不代表本项目实现了完整 OAuth/OIDC 协议；具体登录接口是 DocWeave 当前的第一方应用接口。
