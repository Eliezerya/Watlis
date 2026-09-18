package com.watlis.app;

import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Editable routing for the existing chapter/manga JSON contract, not executable scripts. */
final class ImportProvider {
    private static final Pattern UUID_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    String id = UUID.randomUUID().toString();
    String name = "", hosts = "", apiBase = "https://api.shngm.io/v1/";
    String linkPath = "/chapter/{id}", chapterEndpoint = "chapter/detail/{id}", mangaEndpoint = "manga/detail/{id}";
    String imageHosts = "assets.shngm.id\nassets.shngm.io";
    boolean enabled = true, uuidIds = true;

    static ImportProvider shinigami() {
        ImportProvider provider = new ImportProvider();
        provider.id = "shinigami-default"; provider.name = "Shinigami";
        provider.hosts = "shinigami.asia\n*.shinigami.asia";
        return provider;
    }

    ImportProvider copy() {
        try { return fromJson(toJson()); } catch (JSONException error) { throw new IllegalStateException(error); }
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id",id).put("name",name).put("hosts",hosts).put("apiBase",apiBase)
                .put("linkPath",linkPath).put("chapterEndpoint",chapterEndpoint).put("mangaEndpoint",mangaEndpoint)
                .put("imageHosts",imageHosts).put("enabled",enabled).put("uuidIds",uuidIds);
    }

    static ImportProvider fromJson(JSONObject json) throws JSONException {
        ImportProvider p = new ImportProvider();
        p.id=json.getString("id"); p.name=json.getString("name"); p.hosts=json.getString("hosts");
        p.apiBase=json.getString("apiBase"); p.linkPath=json.getString("linkPath");
        p.chapterEndpoint=json.getString("chapterEndpoint"); p.mangaEndpoint=json.getString("mangaEndpoint");
        p.imageHosts=json.getString("imageHosts"); p.enabled=json.getBoolean("enabled"); p.uuidIds=json.getBoolean("uuidIds");
        p.validate(); return p;
    }

    void validate() {
        name=name.trim();
        if (id == null || id.isEmpty() || name.isEmpty() || name.length()>80)
            throw new IllegalArgumentException("Enter a provider name (up to 80 characters).");
        hosts=normalizeHosts(hosts, false); imageHosts=normalizeHosts(imageHosts, true);
        apiBase=apiBase.trim();
        URI base=httpsUri(apiBase);
        if (base==null || base.getRawQuery()!=null || base.getRawFragment()!=null || !safePath(base.getRawPath()))
            throw new IllegalArgumentException("API base must be an HTTPS URL without credentials, query, fragment or dot segments.");
        if (!apiBase.endsWith("/")) apiBase += "/";
        linkPath=linkPath.trim(); chapterEndpoint=chapterEndpoint.trim(); mangaEndpoint=mangaEndpoint.trim();
        validateTemplate(linkPath,true,"Chapter link path");
        validateTemplate(chapterEndpoint,false,"Chapter endpoint");
        validateTemplate(mangaEndpoint,false,"Manga endpoint");
    }

    private static void validateTemplate(String value, boolean absolutePath, String label) {
        if (value.length()>500 || value.indexOf("{id}")<0 || value.indexOf("{id}")!=value.lastIndexOf("{id}"))
            throw new IllegalArgumentException(label + " must contain {id} exactly once.");
        String path=value.replace("{id}","test-id");
        if (absolutePath != path.startsWith("/") || path.startsWith("//") || !safePath(path)
                || !path.matches("/?[A-Za-z0-9_./~-]+"))
            throw new IllegalArgumentException(label + (absolutePath ? " must be a path like /chapter/{id}." : " must be a relative path like chapter/detail/{id}, without a leading slash."));
    }

    private static boolean safePath(String path) {
        if (path==null) return true;
        if (path.contains("%") || path.contains("\\") || path.contains("//")) return false;
        for (String part:path.split("/")) if (part.equals(".") || part.equals("..")) return false;
        return true;
    }

    static URI httpsUri(String value) {
        try {
            URI uri=new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null
                    || (uri.getPort()!=-1 && uri.getPort()!=443)) return null;
            return uri;
        } catch (Exception ignored) { return null; }
    }

    private static String normalizeHosts(String value, boolean optional) {
        List<String> hosts=new ArrayList<>();
        for (String entry:value.toLowerCase(Locale.ROOT).split("[,\\s]+")) {
            if (entry.isEmpty()) continue;
            String host=entry.startsWith("*.") ? entry.substring(2) : entry;
            if (host.length()>253 || !host.contains(".") || !host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
                    || host.contains("..") || host.matches("[0-9.]+"))
                throw new IllegalArgumentException("Use domain names only, such as reader.example.com or *.example.com; no URLs, ports or IP addresses.");
            for (String part:host.split("\\.")) if (part.length()>63 || part.startsWith("-") || part.endsWith("-"))
                throw new IllegalArgumentException("A domain name is invalid.");
            if (!hosts.contains(entry)) hosts.add(entry);
        }
        if ((!optional && hosts.isEmpty()) || hosts.size()>40)
            throw new IllegalArgumentException("Enter 1–40 website hosts (cover hosts can be empty).");
        return String.join("\n",hosts);
    }

    static boolean allowsHost(String rules,String host) {
        if (host==null) return false;
        host=host.toLowerCase(Locale.ROOT);
        for (String rule:rules.split("\n")) {
            if (rule.startsWith("*.")) { if (host.endsWith(rule.substring(1))) return true; }
            else if (host.equals(rule)) return true;
        }
        return false;
    }

    boolean validId(String value) {
        return value!=null && (uuidIds ? UUID_ID.matcher(value).matches() : value.matches("[A-Za-z0-9_-]{1,160}"));
    }

    String chapterIdFor(String link) {
        if (!enabled || link==null) return null;
        URI uri=httpsUri(link.trim());
        if (uri==null || !allowsHost(hosts,uri.getHost())) return null;
        String path=uri.getRawPath(), template=linkPath;
        if (path==null) return null;
        if (path.endsWith("/")) path=path.substring(0,path.length()-1);
        if (template.endsWith("/")) template=template.substring(0,template.length()-1);
        int index=template.indexOf("{id}");
        if(index<0)return null;
        String prefix=template.substring(0,index), suffix=template.substring(index+4);
        if (!path.startsWith(prefix) || !path.endsWith(suffix) || path.length()<prefix.length()+suffix.length()) return null;
        String id=path.substring(prefix.length(),path.length()-suffix.length());
        return validId(id) ? (uuidIds ? id.toLowerCase(Locale.ROOT) : id) : null;
    }

    String endpoint(boolean chapter,String id) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid response ID.");
        return apiBase+(chapter ? chapterEndpoint : mangaEndpoint).replace("{id}",id);
    }

    String safeImage(String value) {
        URI uri=httpsUri(value);
        return uri!=null && allowsHost(imageHosts,uri.getHost()) ? value : "";
    }
}
