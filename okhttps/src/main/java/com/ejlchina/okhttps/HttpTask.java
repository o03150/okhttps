package com.ejlchina.okhttps;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.ejlchina.okhttps.HttpResult.State;
import com.ejlchina.okhttps.internal.*;
import com.ejlchina.okhttps.internal.HttpClient.TagTask;

import okhttp3.*;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpMethod;
import okio.Buffer;

/**
 * Created by 周旭（Troy.Zhou） on 2020/3/11.
 */
@SuppressWarnings("unchecked")
public abstract class HttpTask<C extends HttpTask<?>> implements Cancelable {

    private static final String PATH_PARAM_REGEX = "[A-Za-z0-9_\\-/]*\\{[A-Za-z0-9_\\-]+\\}[A-Za-z0-9_\\-/]*";

    protected HttpClient httpClient;
    protected boolean nothrow;
    protected boolean nextOnIO = false;
    
    private String urlPath;
    private String tag;
    private Map<String, String> headers;
    private Map<String, String> pathParams;
    private Map<String, String> urlParams;
    private Map<String, String> bodyParams;
    private Map<String, FilePara> files;
    private Object requestBody;
    private String dateFormat;
    private String bodyType;
    private OnCallback<Process> onProcess;
    private boolean pOnIO;
    private long stepBytes = 0;
    private double stepRate = -1;

    private Object object;
    
    private TagTask tagTask;
    private Cancelable canceler;
    private Charset charset;

    protected boolean skipPreproc = false;
    protected boolean skipSerialPreproc = false;


    public HttpTask(HttpClient httpClient, String url) {
        this.urlPath = url;
        this.httpClient = httpClient;
        this.charset = httpClient.charset();
        this.bodyType = httpClient.bodyType();
    }

    /**
     * 获取请求任务的URL地址
     * @return URL地址
     */
    public String getUrl() {
        return urlPath;
    }

    /**
     * 获取请求任务的标签
     * @return 标签
     */
    public String getTag() {
        return tag;
    }

    public String getBodyType() {
        return bodyType;
    }

    /**
     * 标签匹配
     * 判断任务标签与指定的标签是否匹配（包含指定的标签）
     * @param tag 标签
     * @return 是否匹配
     */
    public boolean isTagged(String tag) {
        if (this.tag != null && tag != null) {
            return this.tag.contains(tag);
        }
        return false;
    }

    /**
     * 获取请求任务的头信息
     * @return 头信息
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * 获得被绑定的对象
     * @return Object
     */
    public Object getBound() {
        return object;
    }

    /**
     * 设置在发生异常时不向上抛出，设置后：
     * 异步请求可以在异常回调内捕获异常，同步请求在返回结果中找到该异常
     * @return HttpTask 实例
     */
    public C nothrow() {
        this.nothrow = true;
        return (C) this;
    }

    /**
     * 指定该请求跳过任何预处理器（包括串行和并行）
     * @return HttpTask 实例
     */
    public C skipPreproc() {
		this.skipPreproc = true;
		return (C) this;
	}

    /**
     * 指定该请求跳过任何串行预处理器
     * @return HttpTask 实例
     */
	public C skipSerialPreproc() {
		this.skipSerialPreproc = true;
		return (C) this;
	}

    @Deprecated
    public C setTag(String tag) {
	    return tag(tag);
    }

    /**
     * @since 2.2.0
     * 为请求任务添加标签
     * @param tag 标签
     * @return HttpTask 实例
     */
    public C tag(String tag) {
        if (tag != null) {
            if (this.tag != null) {
                this.tag = this.tag + "." + tag;
            } else {
                this.tag = tag;
            }
            updateTagTask();
        }
        return (C) this;
    }

    /**
     * @since 2.0.0
     * 设置该请求的编码格式
     * @param charset 编码格式
     * @return HttpTask 实例
     */
    public C charset(Charset charset) {
        if (charset != null) {
            this.charset = charset;
        }
        return (C) this;
    }

    /**
     * @since 2.0.0
     * 设置请求体的类型，如：form、json、xml、protobuf 等
     * @param type 请求类型
     * @return HttpTask 实例
     */
    public C bodyType(String type) {
        if (type != null) {
            this.bodyType = type;
        }
        return (C) this;
    }

    /**
     * 下一个回调在IO线程执行
     * @return HttpTask 实例
     */
    public C nextOnIO() {
        nextOnIO = true;
        return (C) this;
    }

    /**
     * 绑定一个对象
     * @param object 对象
     * @return HttpTask 实例
     */
    public C bind(Object object) {
        this.object = object;
        return (C) this;
    }

	/**
     * 添加请求头
     * @param name 请求头名
     * @param value 请求头值
     * @return HttpTask 实例
     */
    public C addHeader(String name, String value) {
        if (name != null && value != null) {
            if (headers == null) {
                headers = new HashMap<>();
            }
            headers.put(name, value);
        }
        return (C) this;
    }

    /**
     * 添加请求头
     * @param headers 请求头集合
     * @return HttpTask 实例
     */
    public C addHeader(Map<String, String> headers) {
        if (headers != null) {
            if (this.headers == null) {
                this.headers = new HashMap<>();
            }
            this.headers.putAll(headers);
        }
        return (C) this;
    }

    /**
     * 设置Range头信息
     * 表示接收报文体时跳过的字节数，用于断点续传
     * @param rangeStart 表示从 rangeStart 个字节处开始接收，通常是已经下载的字节数，即上次的断点）
     * @return HttpTask 实例
     */
    public C setRange(long rangeStart) {
        return addHeader("Range", "bytes=" + rangeStart + "-");
    }

    /**
     * 设置Range头信息
     * 设置接收报文体时接收的范围，用于分块下载
     * @param rangeStart 表示从 rangeStart 个字节处开始接收
     * @param rangeEnd 表示接收到 rangeEnd 个字节处
     * @return HttpTask 实例
     */
    public C setRange(long rangeStart, long rangeEnd) {
        return addHeader("Range", "bytes=" + rangeStart + "-" + rangeEnd);
    }

    /**
     * 设置报文体发送进度回调
     * @param onProcess 进度回调函数
     * @return HttpTask 实例
     */
    public C setOnProcess(OnCallback<Process> onProcess) {
        this.onProcess = onProcess;
        pOnIO = nextOnIO;
        nextOnIO = false;
        return (C) this;
    }

    /**
     * 设置进度回调的步进字节，默认 8K（8192）
     * 表示每接收 stepBytes 个字节，执行一次进度回调
     * @param stepBytes 步进字节
     * @return HttpTask 实例
     */
    public C stepBytes(long stepBytes) {
        this.stepBytes = stepBytes;
        return (C) this;
    }
    
    @Deprecated
    public C setStepBytes(long stepBytes) {
        return stepBytes(stepBytes);
    }

    /**
     * 设置进度回调的步进比例
     * 表示每接收 stepRate 比例，执行一次进度回调
     * @param stepRate 步进比例
     * @return HttpTask 实例
     */
    public C stepRate(double stepRate) {
        this.stepRate = stepRate;
        return (C) this;
    }
    
    @Deprecated
    public C setStepRate(double stepRate) {
        return stepRate(stepRate);
    }

    @Deprecated
    public C addPathParam(String name, Object value) {
        return addPathPara(name, value);
    }

    /**
     * 路径参数：替换URL里的{name}
     * @param name 参数名
     * @param value 参数值
     * @return HttpTask 实例
     **/
    public C addPathPara(String name, Object value) {
        if (name != null && value != null) {
            if (pathParams == null) {
                pathParams = new HashMap<>();
            }
            pathParams.put(name, value.toString());
        }
        return (C) this;
    }

    @Deprecated
    public C addPathParam(Map<String, ?> params) {
        return addPathPara(params);
    }

    /**
     * 路径参数：替换URL里的{name}
     * @param params 参数集合
     * @return HttpTask 实例
     **/
    public C addPathPara(Map<String, ?> params) {
        if (pathParams == null) {
            pathParams = new HashMap<>();
        }
        doAddParams(pathParams, params);
        return (C) this;
    }

    @Deprecated
    public C addUrlParam(String name, Object value) {
        return addUrlPara(name, value);
    }

    /**
     * URL参数：拼接在URL后的参数
     * @param name 参数名
     * @param value 参数值
     * @return HttpTask 实例
     **/
    public C addUrlPara(String name, Object value) {
        if (name != null && value != null) {
            if (urlParams == null) {
                urlParams = new HashMap<>();
            }
            urlParams.put(name, value.toString());
        }
        return (C) this;
    }

    @Deprecated
    public C addUrlParam(Map<String, ?> params) {
        return addUrlPara(params);
    }

    /**
     * URL参数：拼接在URL后的参数
     * @param params 参数集合
     * @return HttpTask 实例
     **/
    public C addUrlPara(Map<String, ?> params) {
        if (urlParams == null) {
            urlParams = new HashMap<>();
        }
        doAddParams(urlParams, params);
        return (C) this;
    }

    @Deprecated
    public C addBodyParam(String name, Object value) {
        return addBodyPara(name, value);
    }

    /**
     * Body参数：放在Body里的参数
     * @param name 参数名
     * @param value 参数值
     * @return HttpTask 实例
     **/
    public C addBodyPara(String name, Object value) {
        if (name != null && value != null) {
            if (bodyParams == null) {
                bodyParams = new HashMap<>();
            }
            bodyParams.put(name, value.toString());
        }
        return (C) this;
    }

    @Deprecated
    public C addBodyParam(Map<String, ?> params) {
        return addBodyPara(params);
    }

    /**
     * Body参数：放在Body里的参数
     * @param params 参数集合
     * @return HttpTask 实例
     **/
    public C addBodyPara(Map<String, ?> params) {
        if (bodyParams == null) {
            bodyParams = new HashMap<>();
        }
        doAddParams(bodyParams, params);
        return (C) this;
    }

    private void doAddParams(Map<String, String> taskParams, Map<String, ?> params) {
        if (params != null) {
            for (String name : params.keySet()) {
                Object value = params.get(name);
                if (name != null && value != null) {
                    taskParams.put(name, value.toString());
                }
            }
        }
    }

    /**
     * 推荐方案：setBodyPara 与 bodyType 方法
     * Json参数：请求体为Json，支持多层结构
     * @param name JSON键名
     * @param value JSON键值
     * @return HttpTask 实例
     */
    @Deprecated
    public C addJsonParam(String name, Object value) {
        this.bodyType = OkHttps.JSON;
        return addBodyPara(name, value);
    }

    /**
     * 推荐方案：setBodyPara 与 bodyType 方法
     * Json参数：请求体为Json，只支持单层Json
     * 若请求json为多层结构，请使用setRequestJson方法
     * @param params JSON键值集合
     * @return HttpTask 实例
     */
    @Deprecated
    public C addJsonParam(Map<String, ?> params) {
        this.bodyType = OkHttps.JSON;
        return addBodyPara(params);
    }

    /**
     * 推荐方案：setBodyPara 与 bodyType 方法
     * 设置 json 请求体
     * @param body JSON字符串 或 Java对象（将依据 对象的get方法序列化为 json 字符串）
     * @return HttpTask 实例
     **/
    @Deprecated
    public C setRequestJson(Object body) {
        this.bodyType = OkHttps.JSON;
        return setBodyPara(body);
    }

    /**
     * 此方法性能较低
     * 推荐方案：setBodyPara 与 bodyType 方法，日期格式在 Java Bean 上使用注解的方式指定
     * @param body Json 请求体
     * @param dateFormat 日期格式
     * @return HttpTask 实例
     */
    @Deprecated
    public C setRequestJson(Object body, String dateFormat) {
        this.bodyType = OkHttps.JSON;
        this.dateFormat = dateFormat;
        return setBodyPara(body);
    }

    /**
     * 设置 json 请求体
     * @param body 请求体，字节数组、字符串 或 Java对象（由 MsgConvertor 来序列化）
     * @return HttpTask 实例
     **/
    public C setBodyPara(Object body) {
        this.requestBody = body;
        return (C) this;
    }

    @Deprecated
    public C addFileParam(String name, String filePath) {
        return addFilePara(name, filePath);
    }

    /**
     * 添加文件参数
     * @param name 参数名
     * @param filePath 文件路径
     * @return HttpTask 实例
     */
    public C addFilePara(String name, String filePath) {
        return addFilePara(name, new File(filePath));
    }

    @Deprecated
    public C addFileParam(String name, File file) {
        return addFilePara(name, file);
    }

    /**
     * 添加文件参数
     * @param name 参数名
     * @param file 文件
     * @return HttpTask 实例
     */
    public C addFilePara(String name, File file) {
        if (name != null && file != null && file.exists()) {
            String fileName = file.getName();
            String type = fileName.substring(fileName.lastIndexOf(".") + 1);
            if (files == null) {
                files = new HashMap<>();
            }
            files.put(name, new FilePara(type, fileName, file));
        }
        return (C) this;
    }

    /**
     * 建议直接使用文件上传，使用输入流性能较差
     * 添加文件参数
     * @param name 参数名
     * @param type 文件类型: 如 png、jpg、jpeg 等
     * @param inputStream 文件输入流
     * @return HttpTask 实例
     */
    @Deprecated
    public C addFileParam(String name, String type, InputStream inputStream) {
        return addFileParam(name, type, null, inputStream);
    }

    /**
     * 建议直接使用文件上传，使用输入流性能较差
     * 添加文件参数
     * @param name 参数名
     * @param type 文件类型: 如 png、jpg、jpeg 等
     * @param fileName 文件名
     * @param input 文件输入流
     * @return HttpTask 实例
     */
    @Deprecated
    public C addFileParam(String name, String type, String fileName, InputStream input) {
        if (name != null && input != null) {
            byte[] content = null;
            try {
                Buffer buffer = new Buffer();
                content = buffer.readFrom(input).readByteArray();
                buffer.close();
            } catch (IOException e) {
                throw new HttpException("读取文件输入流出错：", e);
            } finally {
                Util.closeQuietly(input);
            }
            addFilePara(name, type, fileName, content);
        }
        return (C) this;
    }

    @Deprecated
    public C addFileParam(String name, String type, byte[] content) {
        return addFilePara(name, type, content);
    }

    /**
     * 添加文件参数
     * @param name 参数名
     * @param type 文件类型: 如 png、jpg、jpeg 等
     * @param content 文件内容
     * @return HttpTask 实例
     */
    public C addFilePara(String name, String type, byte[] content) {
        return addFilePara(name, type, null, content);
    }

    @Deprecated
    public C addFileParam(String name, String type, String fileName, byte[] content) {
        return addFilePara(name, type, fileName, content);
    }

    /**
     * 添加文件参数
     * @param name 参数名
     * @param type 文件类型: 如 png、jpg、jpeg 等
     * @param fileName 文件名
     * @param content 文件内容
     * @return HttpTask 实例
     */
    public C addFilePara(String name, String type, String fileName, byte[] content) {
        if (name != null && content != null) {
            if (files == null) {
                files = new HashMap<>();
            }
            files.put(name, new FilePara(type, fileName, content));
        }
        return (C) this;
    }

    @Override
    public boolean cancel() {
        if (canceler != null) {
            return canceler.cancel();
        }
        return false;
    }

    static class FilePara {

        String type;
        String fileName;
        byte[] content;
        File file;

        FilePara(String type, String fileName, byte[] content) {
            this.type = type;
            this.fileName = fileName;
            this.content = content;
        }

        FilePara(String type, String fileName, File file) {
            this.type = type;
            this.fileName = fileName;
            this.file = file;
        }

    }
    
    protected void registeTagTask(Cancelable canceler) {
        if (tag != null && tagTask == null) {
            tagTask = httpClient.addTagTask(tag, canceler, this);
        }
        this.canceler = canceler;
    }

    private void updateTagTask() {
        if (tagTask != null) {
            tagTask.setTag(tag);
        } else 
        if (canceler != null) {
            registeTagTask(canceler);
        }
    }
    
    protected void removeTagTask() {
        if (tag != null) {
            httpClient.removeTagTask(this);
        }
    }

    protected Call prepareCall(String method) {
        Request request = prepareRequest(method);
		return httpClient.request(request);
    }

    protected Request prepareRequest(String method) {
        boolean bodyCanUsed = HttpMethod.permitsRequestBody(method);
        assertNotConflict(!bodyCanUsed);
		Request.Builder builder = new Request.Builder()
                .url(buildUrlPath());
        buildHeaders(builder);
        if (bodyCanUsed) {
            RequestBody reqBody = buildRequestBody();
            if (onProcess != null) {
                long contentLength = contentLength(reqBody);
                if (stepRate > 0 && stepRate <= 1) {
                    stepBytes = (long) (contentLength * stepRate);
                }
                if (stepBytes <= 0) {
                    stepBytes = Process.DEFAULT_STEP_BYTES;
                }
                reqBody = new ProcessRequestBody(reqBody, onProcess,
                        httpClient.executor().getExecutor(pOnIO),
                        contentLength, stepBytes);
            }
            builder.method(method, reqBody);
        } else {
            builder.method(method, null);
        }
		return builder.build();
	}

    private long contentLength(RequestBody reqBody) {
        try {
            return reqBody.contentLength();
        } catch (IOException e) {
            throw new HttpException("无法获取请求体长度", e);
        }
    }

    private void buildHeaders(Request.Builder builder) {
        if (headers != null) {
            for (String name : headers.keySet()) {
                String value = headers.get(name);
                if (value != null) {
                    builder.addHeader(name, value);
                }
            }
        }
    }

    protected State toState(IOException e, boolean sync) {
        if (e instanceof SocketTimeoutException) {
            return State.TIMEOUT;
        } else if (e instanceof UnknownHostException || e instanceof ConnectException) {
            return State.NETWORK_ERROR;
        }
        String msg = e.getMessage();
        if (msg != null && ("Canceled".equals(msg)
                || sync && e instanceof SocketException
                && msg.startsWith("Socket operation on nonsocket"))) {
            return State.CANCELED;
        }
        return State.EXCEPTION;
    }

    private RequestBody buildRequestBody() {
        if (files != null) {
            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
            if (bodyParams != null) {
                for (String name : bodyParams.keySet()) {
                    byte[] value = bodyParams.get(name).getBytes(charset);
                    RequestBody body = RequestBody.create(null, value);
                    builder.addPart(MultipartBody.Part.createFormData(name, null, body));
                }
            }
            for (String name : files.keySet()) {
                FilePara file = files.get(name);
                MediaType type = httpClient.mediaType(file.type);
                RequestBody bodyPart;
                if (file.file != null) {
                    bodyPart = RequestBody.create(type, file.file);
                } else {
                    bodyPart = RequestBody.create(type, file.content);
                }
                builder.addFormDataPart(name, file.fileName, bodyPart);
            }
            return builder.build();
        }
        if (requestBody != null) {
            return toRequestBody(requestBody);
        }
        if (bodyParams == null) {
            return new FormBody.Builder(charset).build();
        }
        if (OkHttps.FORM.equalsIgnoreCase(bodyType)) {
            FormBody.Builder builder = new FormBody.Builder(charset);
            for (String name : bodyParams.keySet()) {
                String value = bodyParams.get(name);
                builder.add(name, value);
            }
            return builder.build();
        }
        return toRequestBody(bodyParams);
    }

    private RequestBody toRequestBody(Object object) {
        if (object instanceof byte[] || object instanceof String) {
            String mediaType = httpClient.executor().doMsgConvert(bodyType, null).mediaType;
            byte[] body = object instanceof byte[] ? (byte[]) object : ((String) object).getBytes(charset);
            return RequestBody.create(MediaType.parse(mediaType + "; charset=" + charset.name()), body);
        }
        TaskExecutor.Data<byte[]> data = httpClient.executor()
                .doMsgConvert(bodyType, (MsgConvertor c) -> c.serialize(object, dateFormat, charset));
        return RequestBody.create(MediaType.parse(data.mediaType + "; charset=" + charset.name()), data.data);
    }

    private String buildUrlPath() {
        String url = urlPath;
        if (url == null || url.trim().isEmpty()) {
            throw new HttpException("url 不能为空！");
        }
        if (pathParams != null) {
            for (String name : pathParams.keySet()) {
                String target = "{" + name + "}";
                if (url.contains(target)) {
                    url = url.replace(target, pathParams.get(name));
                } else {
                    throw new HttpException("pathParameter [ " + name + " ] 不存在于 url [ " + urlPath + " ]");
                }
            }
        }
        if (url.matches(PATH_PARAM_REGEX)) {
            throw new HttpException("url 里有 pathParameter 没有设置，你必须先调用 addPathParam 为其设置！");
        }
        if (urlParams != null) {
            url = buildUrl(url.trim());
        }
        return url;
    }

    private String buildUrl(String url) {
        StringBuilder sb = new StringBuilder(url);
        if (url.contains("?")) {
            if (!url.endsWith("?")) {
                if (url.lastIndexOf("=") < url.lastIndexOf("?") + 2) {
                    throw new HttpException("url 格式错误，'？' 后没有发现 '='");
                }
                if (!url.endsWith("&")) {
                    sb.append('&');
                }
            }
        } else {
            sb.append('?');
        }
        for (String name : urlParams.keySet()) {
            sb.append(name).append('=').append(urlParams.get(name)).append('&');
        }
        sb.delete(sb.length() - 1, sb.length());
        return sb.toString();
    }

    protected void assertNotConflict(boolean bodyCantUsed) {
        if (bodyCantUsed) {
            if (requestBody != null) {
                throw new HttpException("GET | HEAD 请求 不能调用 setBodyPara 方法！");
            }
            if (bodyParams != null) {
                throw new HttpException("GET | HEAD 请求 不能调用 addBodyPara 方法！");
            }
            if (files != null) {
                throw new HttpException("GET | HEAD 请求 不能调用 addFilePara 方法！");
            }
        }
        if (requestBody != null) {
            if (bodyParams != null) {
                throw new HttpException("方法 addBodyPara 与 setBodyPara 不能同时调用！");
            }
            if (files != null) {
                throw new HttpException("方法 addFilePara 与 setBodyPara 不能同时调用！");
            }
        }
    }

    /**
     * @param latch CountDownLatch
     * @return 是否未超时：false 表示已超时
     */
    protected boolean timeoutAwait(CountDownLatch latch) {
        try {
            return latch.await(httpClient.preprocTimeoutMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new HttpException("超时", e);
        }
    }

    protected HttpResult timeoutResult() {
        if (nothrow) {
            return new RealHttpResult(this, State.TIMEOUT);
        }
        throw new HttpException(State.TIMEOUT, "执行超时");
    }

    public Charset charset(Response response) {
        ResponseBody b = response.body();
        MediaType type = b != null ? b.contentType() : null;
        return type != null ? type.charset(charset) : charset;
    }

}
