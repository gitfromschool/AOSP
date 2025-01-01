import os
def iter(p):
    print(p)
    for f in os.listdir(p):
        f=p+"/"+f
        if os.path.isdir(f):
            iter(f)
            continue
        print(f)
        try:
            c=open(f).read().replace("aosp.defaultandroid", "aosp.defaultandroidandroid")
            # print(c)

            with open(f,"w") as fs:
                fs.write(c)
                fs.flush()
                fs.close()
        except Exception as e: ...#print("---", f, e)

iter(".")