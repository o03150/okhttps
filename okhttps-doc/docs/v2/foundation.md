---
description: OkHttps 请求方法 回调函数 HttpResult cache 多次 消费报文体 HttpCall 构建HTTP任务 请求参数 JSON 使用标签 发起请求 取消请求
---

# 基础

## 请求方法

OkHttps 使用`sync()`和`async()`方式发起的请求，支持的 HTTP 方法有：

HTTP 请求方法 | 实现方法 | Restful 释义 | 起始版本
-|-|-|-
GET | `get()` | 获取资源 | 1.0.0
HEAD | `head()` | 获取资源头信息 | 2.0.0（即将发布）
POST | `post()` | 提交资源 | 1.0.0
PUT | `put()` | 更新资源 | 1.0.0
PATCH | `patch()` | 部分更新资源 | 2.0.0.RC
DELETE | `delete()` | 删除资源 | 1.0.0
其它 | `request(String method)` | 任何 HTTP 请求方法 | 2.0.0.RC

例如，修改 ID 为 100 的书的标题，可以这样请求：

```java
http.async("/books/100")
        .addBodyPara("title", "新标题")
        .patch();                   // 发起 PATCH 请求
```

再如，删除 ID 为 100 的书，可以发起这样的请求：

```java
http.async("/books/100").delete();  // 发起 DELETE 请求
```

再如，使用 HEAD 请求获取服务器上文件的大小：

```java
HttpResult res = http.sync("/download/file.zip").head();
// // HttpResult#getContentLength() 新增于 2.0.0 版本
System.out.println("size = " + res.getContentLength());
```

你还可以自定义一些请求方法，例如：

```java
http.async("/somethings")
        .setOnResponse((HttpResult result) -> {
            System.out.println(result);
        })
        .request("OPTIONS");        // 发起 OPTIONS 请求
```

另外，同步请求的所有这些方法都会返回一个[`HttpResult`](#httpresult)，而异步请求则会返回一个[`HttpCall`](#httpcall)：

```java
HttpResult res1 = http.sync("/books").get();     // 同步 GET
HttpResult res2 = http.sync("/books").post();    // 同步 POST

HttpCall call1 = http.async("/books").get();     // 异步 GET
HttpCall call2 = http.async("/books").post();    // 异步 POST
```

## 回调函数

OkHttps 的回调函数全部使用单方法模式，这样可以充分利用 Java8 或 Kotlin 中的 Lambda 表达式，使你的代码更加简洁优雅：

### 普通回调

```java
http.async("/users")        // http://api.demo.com/users
        .setOnResponse((HttpResult result) -> {
            // 响应回调
        })
        .setOnException((IOException e) -> {
            // 异常回调
        })
        .setOnComplete((State state) -> {
            // 完成回调，无论成功失败都会执行，并且在 响应|异常回调 之前执行
            // 可以根据 state 枚举判断执行状态:
            // State.CANCELED`      请求被取消
            // State.RESPONSED`     已收到响应
            // State.TIMEOUT`       请求超时
            // State.NETWORK_ERROR` 网络错误
            // State.EXCEPTION`     其它请求异常
        })
        .get();
```

OkHttps 同时还支持 **全局回调** 和 **回调阻断** 机制，详见 [全局回调监听](/v2/configuration.html#全局回调监听)。

::: tip
* 只有异步请求才可以设置这三种（响应|异常|完成）回调
* 同步请求直接返回结果，无需使用回调
:::

### 进度回调

上传进度回调：

```java
http.sync("/upload")
        .addFilePara("avatar", "D:/image/avatar.jpg")
        .setOnProcess((Process process) -> { 
            long doneBytes = process.getDoneBytes();   // 已上传字节数
            long totalBytes = process.getTotalBytes(); // 总共的字节数
            double rate = process.getRate();           // 已上传的比例
            boolean isDone = process.isDone();         // 是否上传完成
        })
        .post();
```

关于上传的更多细节请参考 [文件上传](/v2/updown.html#文件上传) 章节。

下载进度回调：

```java
http.sync("/download/test.zip").get().getBody()
        .setOnProcess((Process process) -> {
            long doneBytes = process.getDoneBytes();   // 已下载字节数
            long totalBytes = process.getTotalBytes(); // 总共的字节数
            double rate = process.getRate();           // 已下载的比例
            boolean isDone = process.isDone();         // 是否下载完成
        })
        .toFolder("D:/download/")        // 指定下载的目录，文件名将根据下载信息自动生成
        .start();
```

#### 还有下载成功或失败的回调：

```java
http.sync("/download/test.zip").get().getBody()
        .toFile("D:/download/test.zip")              // 下载到文件
        .setOnSuccess((File file) -> {
            // 下载成功
        })
        .setOnFailure((Failure failure) -> {
            // 下载失败
        })
        .start();
```

关于下载的更多内容，请参考 [文件下载](/v2/updown.html#文件下载) 章节。

::: tip
OkHttps 的所有回调函数都默认在 IO 线程执行，若要切换默认线程（例如 Android 中切换到 UI 线程）请参见 [回调线程切换](/v2/android.html#回调线程切换)。
:::

## HttpResult

　　`HttpResult`是HTTP请求执行完后的结果，它是同步请求方法`get()`、`post()`等的返回值，也是异步请求响应回调`OnResponse`的参数，它定义了如下方法：

* `getState()`         得到请求执行状态枚举，它有以下取值：
    * `State.CANCELED`      请求被取消
    * `State.RESPONSED`     已收到响应
    * `State.TIMEOUT`       请求超时
    * `State.NETWORK_ERROR` 网络错误
    * `State.EXCEPTION`     其它请求异常
* `getStatus()`        得到 HTTP 状态码
* `isSuccessful()`     是否响应成功，状态码在 [200..300) 之间
* `getHeaders()`       得到HTTP响应头
* `getHeaders(String name)` 得到HTTP响应头
* `getHeader(String name)`  得到HTTP响应头
* `getContentLength();` 解析响应头中的 Content-Length 长度信息（v2.0.0 新增）
* `getBody()`          得到响应报文体`Body`实例，它定义了如下方法（对同一个`Body`实例，以下的`toXXX()`类方法只能使用一个且仅能调用一次，除非先使用 cache 方法）：
    * `toBytes()`                报文体转换为字节数组
    * `toByteStream()`           报文体转换为字节输入流
    * `toCharStream()`           报文体转换为字符输入流
    * `toString()`               报文体转换为字符串文本
    * `toBean(Class<T> type)`    报文体根据`type`自动反序列化为 JavaBean（依赖`MsgConvertor`）
    * `toList(Class<T> type)`    报文体根据`type`自动反序列化为 JavaBean 列表（依赖`MsgConvertor`）
    * `toMapper()`               报文体自动反序列化为映射结构的对象（依赖`MsgConvertor`，v2.2.0.RC 之前是`toJsonObject()`）
    * `toArray()`                报文体自动反序列化为数组结构的对象（依赖`MsgConvertor`，v2.2.0.RC 之前是`toJsonArray()`）
    * `toFile(String filePath)`  下载到指定路径
    * `toFile(File file)`        下载到指定文件
    * `toFolder(String dirPath)` 下载到指定目录
    * `toFolder(File dir)`       下载到指定目录
    * `getType()`                返回报文体的媒体类型（v2.0.0 新增，不再推荐`getContentType()`方法）
    * `getLength()`              返回报文体的字节长度（v2.0.0 新增，不再推荐`getContentLength()`方法）
    * `cache()`                  缓存报文体，开启缓存后可重复使用`toXXX()`类方法
    * `close()`                  关闭报文体，未对报文体做任何消费时使用，比如只读取报文头
* `getError()`         执行中发生的异常，自动捕获执行请求时发生的 网络超时、网络错误 和 其它IO异常
* `close()`            关闭报文，未对报文体做任何消费时使用，比如只读取长度

示例，请求结果自动反序列化为 Bean 和 List：

```java
// 自动转Bean
Order order = http.sync("/orders/1").get()
        .getBody().toBean(Order.class);

// 自动转List
List<Order> orders = http.sync("/orders")
        .get().getBody().toList(Order.class);
```

上例中，为了获取订单列表，必须先定义一个`Order`类，如果你不想定义它，可以直接使用`toMapper()`和`toArray()`方法：

```java
Mapper order = http.sync("/orders/1")
        .get().getBody().toMapper();

long orderId = order.getLong("id");         // 订单ID
long orderNo = order.getString("orderNo");  // 订单号

Array orders = http.sync("/orders")
        .get().getBody().toArray();

int size = orders.size();                   // 订单个数
Mapper o1 = orders.getMapper(0)             // 第1个订单
```

关于 Mapper 和 Array 的更多信息，请参考 [Mapper 接口](https://gitee.com/ejlchina-zhxu/okhttps/blob/master/okhttps/src/main/java/com/ejlchina/okhttps/Mapper.java) 和 [Array 接口](https://gitee.com/ejlchina-zhxu/okhttps/blob/master/okhttps/src/main/java/com/ejlchina/okhttps/Array.java)。

示例，使用 `cache()` 方法：

```java
Body body = http.sync("/orders").get().getBody().cache();

// 使用 cache 后，可以多次使用 toXXX() 方法
System.out.println(body.toString());
System.out.println(body.toArray());
System.out.println(body.toList(Order.class));
```

示例，获取下载文件的大小：

```java
long size = http.sync("/download/test.zip").get().getBody()
            .close()        // 只是想获得文件大小，不消费报文体，所以直接关闭
            .getLength();   // 获得待下载文件的大小
// 由于未消费报文体，所以本次请求不会消耗下载报文体的时间和网络流量
System.out.println("size = " + size);
```

上述代码等同于：

```java
long size = http.sync("/download/test.zip").get().close().getContentLength(); 
System.out.println("size = " + size);
```

还等效于：

```java
long size = http.sync("/download/test.zip").head().getContentLength(); 
System.out.println("size = " + size);
```

## HttpCall

[`HttpCall`](https://gitee.com/ejlchina-zhxu/okhttps/blob/master/okhttps/src/main/java/com/ejlchina/okhttps/HttpCall.java)是异步请求方法`get()`、`post()`等的返回值，它是一个接口，继承自[`Cancelable`](https://gitee.com/ejlchina-zhxu/okhttps/blob/master/okhttps/src/main/java/com/ejlchina/okhttps/Cancelable.java)，与`java`的`Future`接口很像，它有如下方法：

* `cancel()` 取消本次请求，返回取消结果
* `isCanceled()` 返回请求是否被取消
* `isDone()` 返回是否执行完成，包含取消和失败
* `getResult()` 返回执行结果`HttpResult`对象，若请求未执行完，则挂起当前线程直到执行完成再返回

例如，取消一个异步请求：

```java
HttpCall call = http.async("/users/1").get();

System.out.println(call.isCanceled());     // false

boolean success = call.cancel();           // 取消请求

System.out.println(success);               // true
System.out.println(call.isCanceled());     // true
```

## 构建请求

　　`HTTP`接口的`sync(String url)`、`async(String url)`和`webSocket(String url)`方法返回一个`HttpTask`对象，它实现了[`Cancelable`](https://gitee.com/ejlchina-zhxu/okhttps/blob/master/okhttps/src/main/java/com/ejlchina/okhttps/Cancelable.java)接口，同时也提供了可链式调用的`addXXX`与`setXXX`等系列方法用于构建请求报文。

### 请求头

* `addHeader(String name, String value)`    添加请求头
* `addHeader(Map<String, String> headers)`  添加请求头

### 路径参数

* `addPathPara(String name, Object value)` 添加路径参数：替换 URL 里的`{name}`占位符
* `addPathPara(Map<String, ?> params)`     添加路径参数：替换 URL 里的`{name}`占位符

### 查询参数

* `addUrlPara(String name, Object value)`  添加 URL 参数：拼接在 URL 的`?`之后（查询参数）
* `addUrlPara(Map<String, ?> params)`      添加 URL 参数：拼接在 URL 的`?`之后（查询参数）

### 报文体参数（表单、JSON、XML等）

* `addBodyPara(String name, Object value)` 添加 Body 参数
* `addBodyPara(Map<String, ?> params)`     添加 Body 参数
* `setBodyPara(Object object)`             设置 Body 参数体  

用例完善中...

### 文件参数

* `addFilePara(String name, String filePath)` 上传文件
* `addFilePara(String name, File file)` 上传文件
* `addFilePara(String name, String type, byte[] content)` 上传文件
* `addFilePara(String name, String type, String fileName, byte[] content)` 上传文件



### 其它

* `tag(String tag)` 为HTTP任务添加标签
* `setRange(long rangeStart)` 设置Range头信息，用于[断点续传](/v1/updown.html#实现断点续传)
* `setRange(long rangeStart, long rangeEnd)` 设置 Range 头信息，可用于[分块下载](/v1/updown.html#实现分块下载)
* `bind(Object object)` 绑定一个对象，可用于实现 Android 里的[生命周期绑定](/v1/android.html#生命周期绑定)

## 使用标签

　　有时候我们想对HTTP任务加以分类，这时候可以使用标签功能：

```java
http.async("/users")    //（1）
        .tag("A")
        .get();
        
http.async("/users")    //（2）
        .tag("A.B")
        .get();
        
http.async("/users")    //（3）
        .tag("B")
        .get();
        
http.async("/users")    //（4）
        .tag("B")
        .tag("C")       // 从 v1.0.4 标签将以追加模式添加，等效于 setTag("B.C")
        .get();
        
http.async("/users")    //（5）
        .tag("C")
        .get();
```
　　当使用标签后，就可以按标签批量的对HTTP任务进行取消：

```java
int count = http.cancel("B");  //（2）（3）（4）被取消（取消标签包含"B"的任务）
System.out.println(count);     // 输出 3
```
　　标签除了可以用来取消任务，在预处理器中它也可以发挥作用，请参见 [并行预处理器](/v1/configuration.html#并行预处理器) 与 [串行预处理器（token问题最佳解决方案）](/v1/configuration.html#串行预处理器（token问题最佳解决方案）)。