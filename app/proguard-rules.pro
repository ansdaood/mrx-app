# Native app uses org.json and Java reflection only minimally.
# Do not keep secrets or broad application classes.
-keepclassmembers class org.json.** { *; }
