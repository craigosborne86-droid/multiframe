#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <map>
#include <set>
#include <sstream>
#include <string>
#ifndef MF_LINEAR_DEVELOP_SOURCE
#define MF_LINEAR_DEVELOP_SOURCE "../../app/src/main/cpp/LinearDevelop.cpp"
#endif
#include MF_LINEAR_DEVELOP_SOURCE

// Encoder is linked only because the actual translation unit contains the
// unrelated JPEG entry point. This harness never calls either function.
namespace multiframe::jpeg {
bool encode(const uint8_t*, int, int, size_t, int, int, int,
            const std::function<bool(const unsigned char*, size_t)>&) { return false; }
bool writeAll(int,const unsigned char*,size_t) { return false; }
}

extern "C" void mfActualSharpen(uint8_t* pixels,int width,int height,size_t stride,
                                float amount,float threshold,float maxShift) {
  multiframe::linear::sharpenRgb8(pixels,width,height,stride,amount,threshold,maxShift);
}

static void reference(std::vector<uint8_t>& out,int width,int height) {
  std::vector<float> y(static_cast<size_t>(width)*height);
  for(size_t i=0;i<y.size();++i) y[i]=multiframe::linear::lumaOf(out[i*3],out[i*3+1],out[i*3+2]);
  for(int py=1;py<height-1;++py) for(int x=1;x<width-1;++x) {
    const float* centre=y.data()+static_cast<size_t>(py)*width;
    const float* above=centre-width;const float* below=centre+width;
    float sum=above[x-1];sum+=above[x];sum+=above[x+1];
    sum+=centre[x-1];sum+=centre[x];sum+=centre[x+1];
    sum+=below[x-1];sum+=below[x];sum+=below[x+1];
    const float detail=centre[x]-sum/9.0f;
    if(std::fabs(detail)<3.5f)continue;
    float shift=std::clamp(detail*.45f,-20.f,20.f);
    uint8_t* q=out.data()+(static_cast<size_t>(py)*width+x)*3;
    for(int c=0;c<3;++c)q[c]=multiframe::linear::shiftChannel(q[c],shift);
  }
}

static void write(const std::string& path,const std::vector<uint8_t>& data) {
  std::ofstream f(path,std::ios::binary);f.write(reinterpret_cast<const char*>(data.data()),data.size());
}


static void referenceStrided(std::vector<uint8_t>& image,int width,int height,size_t stride,
                             size_t prefix,float amount,float threshold,float maxShift) {
  if(amount<=0 || width<3 || height<3)return;
  const uint8_t* source=image.data()+prefix;
  std::vector<float> y(static_cast<size_t>(width)*height);
  for(int row=0;row<height;++row)for(int x=0;x<width;++x) {
    const uint8_t* q=source+static_cast<size_t>(row)*stride+x*3;
    y[static_cast<size_t>(row)*width+x]=multiframe::linear::lumaOf(q[0],q[1],q[2]);
  }
  for(int row=1;row<height-1;++row)for(int x=1;x<width-1;++x) {
    const float* centre=y.data()+static_cast<size_t>(row)*width;
    const float* above=centre-width;const float* below=centre+width;
    float sum=above[x-1];sum+=above[x];sum+=above[x+1];
    sum+=centre[x-1];sum+=centre[x];sum+=centre[x+1];
    sum+=below[x-1];sum+=below[x];sum+=below[x+1];
    const float detail=centre[x]-sum/9.0f;
    if(std::fabs(detail)<threshold)continue;
    const float shift=std::clamp(detail*amount,-maxShift,maxShift);
    uint8_t* q=image.data()+prefix+static_cast<size_t>(row)*stride+x*3;
    for(int c=0;c<3;++c)q[c]=multiframe::linear::shiftChannel(q[c],shift);
  }
}

static void extraCases(const std::string& dir) {
  struct Case{const char* name;int width,height,padding;float amount,threshold,maxShift;};
  const Case cases[]={
    {"width1",1,257,11,.45f,3.5f,20.f},
    {"width2",2,17,1,.45f,3.5f,20.f},
    {"height1",257,1,7,.45f,3.5f,20.f},
    {"height2",17,2,0,.45f,3.5f,20.f},
    {"tiny3x3",3,3,0,.45f,3.5f,20.f},
    {"tiny3x8",3,8,13,.45f,3.5f,20.f},
    {"tiny3x9",3,9,1,.45f,3.5f,20.f},
    {"odd_tail",11,17,5,.45f,3.5f,20.f},
    {"padded_stride",257,257,17,.45f,3.5f,20.f},
    {"amount0",257,129,7,0.f,3.5f,20.f},
    {"maxShift0",17,17,9,.45f,3.5f,0.f},
    {"below_threshold",9,17,3,.45f,255.f,20.f},
  };
  std::filesystem::create_directories(dir+"/checks");
  std::ofstream csv(dir+"/checks.csv");
  csv<<"case,width,height,stride,runs,maxCodeError,changedGuards,changedPadding,changedBorders,disabledChanges,uniqueOutputs,referenceAANull\n";
  for(const auto& test:cases) {
    constexpr size_t prefix=64,suffix=64;const size_t stride=static_cast<size_t>(test.width)*3+test.padding;
    std::vector<uint8_t> input(prefix+stride*test.height+suffix,0xa5);
    uint32_t seed=0x13d09a4bu;
    for(int y=0;y<test.height;++y) {
      uint8_t* row=input.data()+prefix+static_cast<size_t>(y)*stride;
      for(int x=0;x<test.width;++x)for(int c=0;c<3;++c) {
        seed^=seed<<13;seed^=seed>>17;seed^=seed<<5;row[x*3+c]=16+seed%224;
      }
      std::fill(row+test.width*3,row+stride,0xc7);
    }
    auto expected=input;referenceStrided(expected,test.width,test.height,stride,prefix,test.amount,test.threshold,test.maxShift);
    auto null=input;referenceStrided(null,test.width,test.height,stride,prefix,test.amount,test.threshold,test.maxShift);
    if(null!=expected)throw std::runtime_error("Scalar reference A/A failed");
    write(dir+"/checks/"+test.name+"-input.bin",input);
    write(dir+"/checks/"+test.name+"-reference.bin",expected);
    int maxError=0;size_t guards=0,padding=0,borders=0,disabled=0;std::set<std::vector<uint8_t>> unique;
    for(int rep=0;rep<8;++rep) {
      auto actual=input;mfActualSharpen(actual.data()+prefix,test.width,test.height,stride,test.amount,test.threshold,test.maxShift);
      for(size_t i=0;i<actual.size();++i) {
        const bool guard=i<prefix || i>=prefix+stride*test.height;
        if(guard){guards+=actual[i]!=input[i];continue;}
        const size_t at=i-prefix;const size_t y=at/stride;const size_t x=(at%stride)/3;
        if(at%stride>=static_cast<size_t>(test.width)*3){padding+=actual[i]!=input[i];continue;}
        if(y==0 || y==static_cast<size_t>(test.height-1) || x==0 || x==static_cast<size_t>(test.width-1))borders+=actual[i]!=input[i];
        maxError=std::max(maxError,std::abs(static_cast<int>(actual[i])-expected[i]));
        if(test.amount<=0 || test.width<3 || test.height<3 || test.maxShift==0 || test.threshold==255)disabled+=actual[i]!=input[i];
      }
      unique.insert(actual);
      write(dir+"/checks/"+test.name+"-run"+std::to_string(rep)+".bin",actual);
    }
    csv<<test.name<<','<<test.width<<','<<test.height<<','<<stride<<",8,"<<maxError<<','<<guards<<','<<padding<<','<<borders<<','<<disabled<<','<<unique.size()<<",true\n";
  }
}

int main(int argc,char** argv) {
  const std::string dir=argv[1];const int width=1027,height=2048;
  const unsigned hw=std::thread::hardware_concurrency();
  const int threads=std::min(static_cast<int>(std::max(2u,hw)),height);
  const int bandRows=std::max(8,(height+threads*6-1)/(threads*6));
  std::set<int> boundary;
  for(int row=bandRows;row<height;row+=bandRows){boundary.insert(row-1);boundary.insert(row);}
  std::vector<uint8_t> input(static_cast<size_t>(width)*height*3);
  // Frozen 32-bit integer mixer generates measured-byte RGB detail and several
  // horizontal edges; seed is part of the immutable fixture, not render math.
  uint32_t rng=0x72f18ad5u;
  for(int y=0;y<height;++y)for(int x=0;x<width;++x)for(int c=0;c<3;++c) {
    rng^=rng<<13;rng^=rng>>17;rng^=rng<<5;
    int value=16+static_cast<int>(rng%224);
    if(y%31<5)value=32+static_cast<int>(rng%24);
    if(y%31>=25)value=202+static_cast<int>(rng%24);
    input[(static_cast<size_t>(y)*width+x)*3+c]=static_cast<uint8_t>(value);
  }
  write(dir+"/input.rgb8",input);
  auto expected=input;reference(expected,width,height);write(dir+"/reference.rgb8",expected);
  auto null=input;reference(null,width,height);
  if(null!=expected){std::cerr<<"Reference A/A failure\n";return 2;}
  std::ofstream summary(dir+"/runs.csv");
  summary<<"run,changedChannels,maxCodeError,boundaryChanged,nonBoundaryChanged,maxBoundaryError,maxNonBoundaryError,firstChangedX,firstChangedY,firstChangedC,changedRows\n";
  for(int run=0;run<24;++run) {
    auto actual=input;mfActualSharpen(actual.data(),width,height,static_cast<size_t>(width)*3,.45f,3.5f,20.f);
    size_t changed=0,badBoundary=0,badInterior=0;int maxError=0,maxBoundary=0,maxInterior=0;int firstX=-1,firstY=-1,firstC=-1;std::set<int> rows;
    for(int y=0;y<height;++y)for(int x=0;x<width;++x)for(int c=0;c<3;++c) {
      size_t i=(static_cast<size_t>(y)*width+x)*3+c;
      int e=std::abs(static_cast<int>(actual[i])-expected[i]);if(!e)continue;
      if(!changed){firstX=x;firstY=y;firstC=c;}
      ++changed;maxError=std::max(maxError,e);rows.insert(y);
      if(boundary.count(y)){++badBoundary;maxBoundary=std::max(maxBoundary,e);}else{++badInterior;maxInterior=std::max(maxInterior,e);}
    }
    std::ostringstream name;name<<dir<<"/run"<<std::setw(2)<<std::setfill('0')<<run<<".rgb8";write(name.str(),actual);
    summary<<run<<','<<changed<<','<<maxError<<','<<badBoundary<<','<<badInterior<<','<<maxBoundary<<','<<maxInterior<<','<<firstX<<','<<firstY<<','<<firstC<<",\"";
    bool first=true;for(int row:rows){if(!first)summary<<' ';summary<<row;first=false;}summary<<"\"\n";
  }
  extraCases(dir);
  std::ofstream meta(dir+"/metadata.json");
  meta<<"{\"width\":"<<width<<",\"height\":"<<height<<",\"hardwareThreads\":"<<hw<<",\"workerThreads\":"<<threads<<",\"bandRows\":"<<bandRows<<",\"bands\":"<<(height+bandRows-1)/bandRows<<",\"runs\":24,\"referenceAANull\":true,\"amount\":0.45,\"threshold\":3.5,\"maxShift\":20,\"nativeParity\":\"actual host arm64 NEON translation unit versus immutable scalar source-equivalent reference\",\"timingClaim\":false}\n";
}
