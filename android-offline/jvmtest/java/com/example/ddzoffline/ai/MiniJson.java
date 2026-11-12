package com.example.ddzoffline.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（仅用于 JVM 测试读取黄金向量；不进 APK 运行时路径）。
 * 返回 Map&lt;String,Object&gt; / List&lt;Object&gt; / String / Double / Boolean / null。
 */
public final class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("JSON 尾部有多余内容 @" + p.i);
        }
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private char peek() {
        if (i >= s.length()) throw new IllegalArgumentException("JSON 意外结束");
        return s.charAt(i);
    }

    private Object value() {
        char c = peek();
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private void expect(String lit) {
        if (!s.startsWith(lit, i)) throw new IllegalArgumentException("期望 " + lit + " @" + i);
        i += lit.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (peek() != ':') throw new IllegalArgumentException("期望 : @" + i);
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; return m; }
            throw new IllegalArgumentException("期望 , 或 } @" + i);
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; // [
        ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; return l; }
            throw new IllegalArgumentException("期望 , 或 ] @" + i);
        }
    }

    private String string() {
        if (peek() != '"') throw new IllegalArgumentException("期望字符串 @" + i);
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalArgumentException("非法转义 \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && (Character.isDigit(s.charAt(i))
                || s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E'
                || s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        String num = s.substring(start, i);
        if (num.isEmpty()) throw new IllegalArgumentException("非法数字 @" + start);
        return Double.valueOf(num);
    }
}
