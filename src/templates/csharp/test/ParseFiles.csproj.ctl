#var csPackage = globals::getPreprocessorSymbol('cs.package', settings.parserPackage)
<Project Sdk="Microsoft.NET.Sdk">
    <PropertyGroup>
        <OutputType>Exe</OutputType>
        <TargetFramework>net6.0</TargetFramework>
        <RootNamespace>${csPackage}.test</RootNamespace>
    </PropertyGroup>
    <ItemGroup>
      <Reference Include="${csPackage}">
        <HintPath>../bin/Debug/net6.0/${csPackage}.dll</HintPath>
      </Reference>
    </ItemGroup>
</Project>
