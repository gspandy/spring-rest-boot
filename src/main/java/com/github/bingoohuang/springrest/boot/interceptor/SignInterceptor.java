package com.github.bingoohuang.springrest.boot.interceptor;

import com.github.bingoohuang.springrest.boot.annotations.RestfulSign;
import com.github.bingoohuang.springrest.boot.filter.BufferedRequestWrapper;
import com.github.bingoohuang.utils.codec.Base64;
import com.github.bingoohuang.utils.net.Http;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.MultiValueMap;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.util.*;

public class SignInterceptor extends HandlerInterceptorAdapter {
    public static final String CLIENT_SECURITY = "d51fd93e-f6c9-4eae-ae7a-9b37af1a60cc";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) return false;

        HandlerMethod method = (HandlerMethod) handler;
        Class<?> beanType = method.getBeanType();
        boolean ignoreSign = ignoreSign(beanType, method);
        Logger logger = LoggerFactory.getLogger("rest." + beanType.getName());

        if (ignoreSign && !logger.isInfoEnabled()) return true;

        String hici = request.getHeader("hici");
        if (StringUtils.isEmpty(hici)) hici = UUID.randomUUID().toString();

        request.setAttribute("_log_hici", hici);
        request.setAttribute("_log_start", System.currentTimeMillis());

        String contentType = request.getContentType();
        String lowerContentType = StringUtils.lowerCase(contentType);
        String requestBody = null;
        if (containsAnyOrNull(lowerContentType, "json", "xml", "text")) {
            BufferedRequestWrapper requestWrapper = (BufferedRequestWrapper) request.getAttribute("_log_req");
            requestBody = requestWrapper.getRequestBody();
        }
        if (StringUtils.isEmpty(requestBody)) requestBody = "(empty)";

        final StringBuilder signStr = new StringBuilder();
        final StringBuilder logStr = new StringBuilder();
        Appendable proxy = new AbbreviateAppendable(logStr, signStr);
        createOriginalStringForSign(proxy, request);
        logger.info("spring rest server {} request {} body: {}", hici, logStr, requestBody);

        if (ignoreSign) return true;

        String hisv = request.getHeader("hisv");
        if (Strings.isNullOrEmpty(hisv)) {
            logger.info("spring rest server {} signature missed", hici);
            Http.error(response, 416, "signature missed");
            return false;
        }

        String sign = hmacSHA256(signStr.toString(), CLIENT_SECURITY);
        boolean signOk = sign.equals(hisv);
        logger.info("spring rest server {} sign result {}", hici, signOk);
        if (!signOk) Http.error(response, 416, "invalid signature");

        return signOk;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        if (!(handler instanceof HandlerMethod)) return;

        HandlerMethod method = (HandlerMethod) handler;
        Class<?> beanType = method.getBeanType();
        Logger logger = LoggerFactory.getLogger("rest." + beanType.getName());
        if (!logger.isInfoEnabled()) return;

        StringBuilder headerSb = new StringBuilder();
        Collection<String> headerNames = response.getHeaderNames();
        Joiner joiner = Joiner.on(',');
        for (String headerName : headerNames) {
            headerSb.append(headerName).append('=');
            Collection<String> headers = response.getHeaders(headerName);
            joiner.join(headers);
            headerSb.append(headers).append('&');
        }
        String contentType = response.getContentType();
        headerSb.append("Content-Type=").append(contentType);

        ByteArrayOutputStream baos = (ByteArrayOutputStream) request.getAttribute("_log_baos");
        StringWriter sw = (StringWriter) request.getAttribute("_log_sw");
        String body = null;

        String lowerContentType = StringUtils.lowerCase(contentType);
        if (containsAnyOrNull(lowerContentType, "json", "xml", "text")) {
            byte[] bytes = baos.toByteArray();
            if (bytes.length > 0) {
                body = new String(bytes, "UTF-8");
            } else {
                body = sw.toString();
            }
        }

        if (body == null || body.contains("<html>")) body = " ignored";

        String hici = (String) request.getAttribute("_log_hici");
        Long start = (Long) request.getAttribute("_log_start");
        long costMillis = System.currentTimeMillis() - start;

        logger.info("spring rest server {} response cost {} millis, status code {}, headers: {}, body: {}",
                hici, costMillis, response.getStatus(), headerSb, body);
    }

    private boolean containsAnyOrNull(String contentType, String... any) {
        if (contentType == null) return true;

        for (String item : any) {
            if (contentType.contains(item)) return true;
        }
        return false;
    }

    private void createOriginalStringForSign(Appendable proxy, HttpServletRequest request) {
        appendMethodAndUrl(request, proxy);
        appendHeaders(request, proxy);
        appendRequestParams(request, proxy);
    }

    private boolean ignoreSign(Class<?> beanType, HandlerMethod method) {
        RestfulSign restfulSign = method.getMethod().getAnnotation(RestfulSign.class);
        if (restfulSign != null) return restfulSign.ignore();

        restfulSign = beanType.getAnnotation(RestfulSign.class);
        if (restfulSign != null) return restfulSign.ignore();
        return true;
    }

    public static String hmacSHA256(String data, String key) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secretKey);
            byte[] hmacData = mac.doFinal(data.getBytes("UTF-8"));
            return Base64.base64(hmacData, Base64.Format.Standard);
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private void appendRequestParams(HttpServletRequest request, Appendable signStr) {
        Map<String, String[]> parameterMap = Maps.newTreeMap();
        parameterMap.putAll(request.getParameterMap());

        String json = getJson(request);
        if (!Strings.isNullOrEmpty(json)) parameterMap.put("_json", new String[]{json});
        fileUpload(request, parameterMap);

        String queryString = request.getQueryString();
        for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
            String parameterName = entry.getKey();
            if (isQueryParameter(queryString, parameterName)) continue;


            signStr.append(parameterName).append('$');
            for (String value : entry.getValue()) {
                signStr.appendAbbreviate(value).append('$');
            }
        }
    }

    private void fileUpload(HttpServletRequest request, Map<String, String[]> parameterMap) {
        if (!(request instanceof MultipartHttpServletRequest)) return;

        MultipartHttpServletRequest mreq = (MultipartHttpServletRequest) request;
        MultiValueMap<String, MultipartFile> multiFileMap = mreq.getMultiFileMap();

        for (Map.Entry<String, List<MultipartFile>> entry : multiFileMap.entrySet()) {
            String name = entry.getKey();

            StringBuilder sb = new StringBuilder();
            List<MultipartFile> value = entry.getValue();
            for (MultipartFile file : value) {
                sb.append(md5(getBytes(file))).append('$');
            }
            if (sb.length() > 0) sb.delete(sb.length() - 1, sb.length());

            parameterMap.put(name, new String[]{sb.toString()});
        }
    }

    private byte[] getBytes(MultipartFile value) {
        try {
            return value.getBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String md5(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            return Base64.base64(digest, Base64.Format.Standard);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String getJson(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return null;

        String contentType = request.getHeader("content-type");
        if (contentType == null) return null;
        if (contentType.indexOf("application/json") < 0) return null;

        try {
            BufferedReader reader = request.getReader();
            return CharStreams.toString(reader);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isQueryParameter(String queryString, String parameterName) {
        if (Strings.isNullOrEmpty(queryString)) return false;

        int index = queryString.indexOf(parameterName);
        if (index < 0) return false;

        if (index > 0 && queryString.charAt(index - 1) != '&') return false;

        int offset = index + parameterName.length();
        if (offset >= queryString.length()) return true;

        return queryString.charAt(offset) == '=';
    }

    private static String[] filtered = new String[]{
            "hisv", "accept-encoding", "user-agent",
            "host", "connection",
            "content-length", "content-type"
    };

    private void appendHeaders(HttpServletRequest request, Appendable signStr) {
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (ArrayUtils.contains(filtered, headerName)) continue;

            Enumeration<String> headers = request.getHeaders(headerName);
            signStr.append(headerName).append('$');

            joinEnumeration(signStr, headers);
        }
    }

    private void joinEnumeration(Appendable signStr, Enumeration<String> headers) {
        while (headers.hasMoreElements()) {
            signStr.append(headers.nextElement()).append('$');
        }
    }

    private void appendMethodAndUrl(HttpServletRequest request, Appendable signStr) {
        signStr.append(request.getMethod()).append('$');

        StringBuilder fullUrl = new StringBuilder(request.getRequestURL());
        String queryString = request.getQueryString();
        if (!Strings.isNullOrEmpty(queryString)) fullUrl.append('?').append(queryString);

        signStr.append(fullUrl.toString()).append('$');
    }

}
