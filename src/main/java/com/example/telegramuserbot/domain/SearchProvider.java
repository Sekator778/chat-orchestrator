package com.example.telegramuserbot.domain;

/**
 * Enumeration of supported search providers
 */
public enum SearchProvider {
    GOOGLE("Google Custom Search"),
    BING("Microsoft Bing Search"),
    DUCKDUCKGO("DuckDuckGo Search"),
    TAVILY("Tavily Search");
    
    private final String displayName;
    
    SearchProvider(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}