// A tool bean of docs/guide/spring-boot.md, compiled and run by SpringBootGuideSpec and Llm4sAgentAutoConfigurationSpec.
// From the first import to the closing brace it is the guide's snippet word for word (the spec checks that), so change
// the guide and this file together.
package org.llm4s.spring;

import org.llm4s.javaapi.Param;
import org.llm4s.javaapi.Tool;
import org.springframework.stereotype.Component;

@Component
public class WeatherTools {

    @Tool(description = "The current weather in a city")
    public String weather(@Param(name = "city", description = "City name") String city) {
        return "Sunny in " + city;
    }
}
