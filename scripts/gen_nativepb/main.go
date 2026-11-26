// gen_nativepb compiles go-core/native/proto/*.proto into go-core/native/pb/*.pb.go.
//
// This machine has no protoc, so the compile step uses protocompile and the code generation
// step calls protoc-gen-go's own generator in-process. The output is the same standard
// generated code protoc + protoc-gen-go would write (AGENTS §3.5: no hand-written wire bytes).
//
//	cd scripts/gen_nativepb && GOSUMDB=off GOFLAGS=-mod=mod go run .
package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"runtime"

	"github.com/bufbuild/protocompile"
	gengo "google.golang.org/protobuf/cmd/protoc-gen-go/internal_gengo"
	"google.golang.org/protobuf/compiler/protogen"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/reflect/protodesc"
	"google.golang.org/protobuf/types/descriptorpb"
	"google.golang.org/protobuf/types/pluginpb"
)

func main() {
	_, self, _, _ := runtime.Caller(0)
	root := filepath.Join(filepath.Dir(self), "..", "..", "go-core")
	protoDir := filepath.Join(root, "native", "proto")
	outDir := filepath.Join(root, "native", "pb")
	files := []string{"cloudkit.proto", "mmcs.proto"}

	c := protocompile.Compiler{
		Resolver: protocompile.WithStandardImports(&protocompile.SourceResolver{ImportPaths: []string{protoDir}}),
	}
	fds, err := c.Compile(context.Background(), files...)
	if err != nil {
		fail("compile: %v", err)
	}
	var protos []*descriptorpb.FileDescriptorProto
	for _, fd := range fds {
		protos = append(protos, protodesc.ToFileDescriptorProto(fd))
	}
	req := &pluginpb.CodeGeneratorRequest{
		FileToGenerate: files,
		ProtoFile:      protos,
		Parameter:      proto.String("paths=source_relative"),
	}
	plugin, err := protogen.Options{}.New(req)
	if err != nil {
		fail("protogen: %v", err)
	}
	plugin.SupportedFeatures = gengo.SupportedFeatures
	for _, f := range plugin.Files {
		if f.Generate {
			gengo.GenerateFile(plugin, f)
		}
	}
	resp := plugin.Response()
	if resp.Error != nil {
		fail("generate: %s", resp.GetError())
	}
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		fail("mkdir: %v", err)
	}
	for _, f := range resp.File {
		out := filepath.Join(outDir, filepath.Base(f.GetName()))
		if err := os.WriteFile(out, []byte(f.GetContent()), 0o644); err != nil {
			fail("write %s: %v", out, err)
		}
		fmt.Println("wrote", out)
	}
}

func fail(format string, a ...interface{}) {
	fmt.Fprintf(os.Stderr, format+"\n", a...)
	os.Exit(1)
}
