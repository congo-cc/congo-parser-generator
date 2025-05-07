<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
#if CI
    <TargetFrameworks>netstandard2.1;net6.0;net8.0</TargetFrameworks>
#else
    <TargetFrameworks>netstandard2.1;net5.0</TargetFrameworks>
/#if
  </PropertyGroup>
</Project>
